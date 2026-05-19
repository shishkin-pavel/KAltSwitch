# Window discovery & classification pipeline

How windows get from macOS into the switcher / inspector. Read this when:

* The switcher is missing or surfacing the wrong rows.
* You're adding a new source of window data (e.g. a private SkyLight enumerator).
* You need to reason about whether a fix belongs Swift-side, in the merge, or
  in the rule layer.

The pipeline has five layers, in order: **enumerate → identify → merge →
classify → present**. Everything below is named accordingly.

---

## 1 — Enumeration (two sources)

Windows enter the system from **two independent Swift-side enumerators**.
Neither sees everything; their union does.

### 1a — `AxAppWatcher` (AX, per-app)

`macosApp/macosApp/AxAppWatcher.swift`

One instance per running app, spawned by `AppRegistry` after walking
`NSWorkspace.shared.runningApplications` at startup and on
`didLaunchApplicationNotification`. Each watcher attaches an `AXObserver` to
its app's `AXUIElement` and listens for `kAXWindowCreated /
WindowMiniaturized / TitleChanged / FocusedWindowChanged / …` — every
notification ends in `refreshAllWindows()`, which reads the app element's
`kAXWindowsAttribute` and rebuilds the tree.

**Critical AX limitation.** `kAXWindowsAttribute` is silently
**space-filtered** by macOS — it returns only windows on the current Mission
Control space. Windows the same app owns on *other* spaces are invisible to
AX. There is no public AX flag to disable this filter. This is the entire
reason we need a second source.

`makeWindow(...)` builds a fully-populated `Window` for each `AXUIElement`:

| Field                 | Source                                      |
| --------------------- | ------------------------------------------- |
| `id: WindowId (Long)` | `Int64(CFHash(axWin))` — process-local hash |
| `pid`                 | the watcher's pid                           |
| `title / role / …`    | `readAttribute(...)` on the AX element      |
| `x / y / width / height` | `kAXPositionAttribute / kAXSizeAttribute` |
| `children`            | recursive, via `kAXChildrenAttribute` + `kAXParentAttribute` re-link |
| `spaceIds`            | `spaceIdsFor(cgWid)` ← `CGSCopySpacesForWindows` |
| `cgWindowId`          | `_AXUIElementGetWindow(axWin, &cgWid)` (private API) |
| CG-only fields        | **all `nil`** — AX doesn't expose them      |

`pushWindowFromElement(axWin)` writes a single Window via
`WorldStore.upsertWindow`; full rebuilds use `WorldStore.setWindows`.

### 1b — `CGWindowListWatcher` (CGWindowList, global)

`macosApp/macosApp/CGWindowListWatcher.swift`

A single global enumerator that calls
`CGWindowListCopyWindowInfo([.optionAll, .excludeDesktopElements],
kCGNullWindowID)`. Unlike AX, this **does** see windows on other spaces —
that's the whole point. It returns a dictionary per window with a fixed
schema; we map five CG-only fields onto `Window` (`isOnscreen`,
`isOnVisibleSpace`, `cgLayer`, `cgAlpha`, `ownerName`) plus the basics
shared with AX (`pid`, `title`, `x / y / width / height`, `spaceIds`,
`cgWindowId`).

**No hard-coded rejects.** Every CG entry crosses the bridge unchanged
(including our own pid — the Inspector and Settings windows are
legitimate cmd+tab targets, and the switcher overlay panel itself is
caught downstream by the built-in `default-hide-cg-overlay-layer` rule
because it sits on a non-zero CG layer). System overlays, tooltip
popovers, zero-alpha stubs, menubar shadows: all filtered downstream
by `FilteringRules`. See `Filters.kt`'s `SeedRules` for the shipped
defaults.

CG-derived `Window`s differ from AX-derived ones in two ways:

1. **All CG-only fields are non-null**, and conversely AX-only fields
   (`role`, `subrole`, `isMinimized`, `isFullscreen`, `isFocused`,
   `isMain`) are *defaults* — `null` / `false`. This asymmetry is
   load-bearing: a CG-side predicate against an AX row evaluates to
   `false` (matches nothing), and vice versa. That gives rule authors
   implicit per-source scoping without an explicit source flag.
2. **`children` is always empty.** CG returns a flat list — no
   parent/child relationships.

**Triggers** (pull-driven; there is no public CG window-change
notification):

1. App startup, once.
2. **Background timer** — `AppRegistry.backgroundRefreshTimer` ticks
   every `backgroundRefreshIntervalSec` (currently 2 s) and calls
   `refresh()`. This is the load-bearing trigger; everything else
   below is a redundant nudge that piggybacks on the same code path.
3. `NSWorkspace.activeSpaceDidChangeNotification` — off-space rows
   turn into on-space rows (and vice versa); refreshing immediately
   avoids the up-to-2-s wait for the timer.
4. Switcher session open (`setOverlayActive(true)` via
   `AppRegistry.refreshCrossSpaceWindows()`). Cheap redundancy:
   protects alternate entry paths (menubar-driven reopen) where the
   timer may have just fired half a second ago.

`refresh()` is **fire-and-forget**: it returns immediately, schedules
the CG / CGS syscalls on a serial background queue, and only the
final `store.replacePhantomWindows` push hops back to main. Multiple
refreshes 50 ms apart coalesce — an in-flight pass absorbs concurrent
requests. No caller (including the cmd+tab hotkey path) ever blocks
the main thread on this work.

Each refresh enumerates from scratch and calls
`WorldStore.replacePhantomWindows(allPhantoms)`. Cost is ≈O(window-count);
on a typical desktop ~250 entries reach the bridge.

### 1c — Why not a third source

* SkyLight private `_SLSCopyWindowsWithOptionsAndTags` — AltTab uses it,
  but it returns *only* CGWindowIDs (no metadata). We'd still need
  `CGWindowListCreateDescriptionFromArray` to back-fill title/bounds, at
  which point we've reimplemented `CGWindowListCopyWindowInfo` with extra
  fragility. Skipped unless we find a class of windows neither AX nor CG
  exposes.
* AX brute-force scan (alt-tab's `_AXUIElementCreateWithRemoteToken`
  iteration) — also skipped; CG covers the same gap with one syscall.

---

## 2 — Identification

`Window.id: Long` is the **identity** used everywhere downstream (activation
log, switcher cursor, raise/commit). It is **process-local** and **not
stable across runs**:

* AX path: `Int64(CFHash(axWin))` — a CoreFoundation hash of the
  `AXUIElement` pointer. Stable for the lifetime of that element.
* CG (phantom) path: `Int64(cgWid)` — the raw `CGWindowID`. Stable for the
  lifetime of the WindowServer's record of the window.

Both can collide in theory. In practice, AX hashes are large 64-bit values
and `CGWindowID`s are small 32-bit numbers, so collisions on the same pid
have never been seen. `WorldStore.setWindows` and friends use `Dictionary
(uniquingKeysWith: { _, new in new })` everywhere a collision would be
fatal.

**The bridge between the two id spaces** is the per-row `cgWindowId: Long?`
field. Populated on the AX side via `_AXUIElementGetWindow`; on the CG side
it equals the id itself. This is the **de-dup key during merge** (§3) and
the **activation-target key** at commit time (§5).

---

## 3 — Storage (`WorldStore`)

`sharedUI/src/commonMain/kotlin/com/shish/kaltswitch/store/WorldStore.kt`

`WorldStore` keeps **one** `_windowsByPid: Map<Pid, List<Window>>` —
the very map that `World.windowsByPid` exposes. No read-time merge.
Every Window in here carries a `sources: Set<WindowSource>` flag
listing which native subsystems currently have it in view (today: `AX`,
`CG`; tomorrow more if we plug in `_SLSCopyWindowsWithOptionsAndTags`
or similar). **A window is dropped exactly when `sources` becomes
empty** — i.e. when every subsystem has stopped seeing it. No source
is privileged.

Public mutators:

| API                                        | Effect                                                                                          |
| ------------------------------------------ | ----------------------------------------------------------------------------------------------- |
| `applyAxSnapshot(pid, axWindows)`          | Reconcile AX-source membership for a single pid. Adds/patches AX-known windows; removes `AX` from `sources` of windows AX previously claimed but no longer sees. Drops only those whose `sources` collapses to empty. |
| `upsertAxWindow(window)`                   | Per-window patch — used by `kAXTitleChanged` / `kAXWindowMiniaturized` / … events. Adds or updates by `cgWindowId` (or `id` fallback); never drops anything. |
| `applyCgSnapshot(allCgWindows)`            | Symmetric global reconciliation for the CG side. Patches `cgLayer` / `isOnscreen` / `ownerName` / etc.; removes `CG` from `sources` of windows missing from the new snapshot. Drops only those whose `sources` collapses to empty. |
| `removeApp(pid)`                           | Kills everything for that pid (terminated apps); same as before.                                |

**Field preservation across source retraction.** When a source
retracts, that source's last-known fields stay on the surviving
Window. A `{AX, CG}` window losing AX keeps its real title (the last
`kAXTitleAttribute` value) instead of falling back to ownerName —
staleness is far less visible than a "title flipped to App Name" flash
mid-cycle.

**Why no privileged source.** The earlier design treated AX as
authoritative on "does this window exist" — AX retraction was fatal.
That worked great for `cmd+W` (instant disappear) but it baked in a
hidden assumption: `kAXUIElementDestroyed` always means destroyed.
The OS doesn't guarantee that. AX can lose visibility of a window on
events that don't reflect destruction (space drag is the obvious
candidate; future macOS quirks are the open-ended set). The current
model handles both uniformly: AX retraction triggers — via the
existing `onCgWindowIdSetChanged` callback in `AxAppWatcher` — a CG
refresh, and the empty-sources drop decision arrives one ~80 ms
off-main round-trip later. Fast enough to feel snappy on `cmd+W`;
correct on the edge cases.

The store-state's invariant: every Window in `_windowsByPid` has at
least one entry in `sources`. The mutators enforce this; the
`WorldStore` constructor normalises any pre-populated `windowsByPid`
(typical in tests) to `sources = {AX}` so the rules don't accidentally
catch sourceless rows.

---

## 4 — Spaces (`spaceIds` + `isOnVisibleSpace`)

`macosApp/macosApp/SkyLight.swift`

Both enumerators populate `Window.spaceIds: List<Long>` via the private
`CGSCopySpacesForWindows(cid, mask=7, [cgWid])`. The CG path additionally
pre-computes `isOnVisibleSpace: Boolean?` by intersecting `spaceIds` with
`currentVisibleSpaceIds()` (= `CGSCopyManagedDisplaySpaces` parsed for the
"Current Space" entry on every connected display) at refresh time.

The pre-computation is deliberate: predicates evaluate without ambient
context, so anything that depends on "the current visible space set" has
to be computed where that set is known (Swift) and shipped with the row.
Re-computing on every predicate evaluation would also require feeding
`WorldStore.visibleSpaceIds` into `Predicate.matches`, which we explicitly
don't.

**Caveats on `spaceIds`.**

* Empty list = "no space data available", **not** "no spaces". Treat it as
  unknown. The classifier's space-mask filter (`maskOffSpace`) and the rules
  layer both treat empty as "skip the filter / unable to evaluate".
* Apps with windows on macOS Spaces don't get extra refreshes when *other*
  spaces' contents change. Cross-space discovery is therefore only as
  fresh as the last `activeSpaceDidChange` or switcher-open.
* `currentVisibleSpaceIds()` reports one space per display when multiple
  displays are connected. A "visible" window is on-current-space if **any**
  of its `spaceIds` is in the visible set (set-intersection, not
  set-equality).

---

## 5 — Classification (`FilteringRules`)

`sharedUI/src/commonMain/kotlin/com/shish/kaltswitch/model/Filters.kt`,
`Predicate.kt`

`World.filteredSnapshot(filters, pinning, currentSpaceOnly,
visibleSpaceIds)` runs every (app, window) pair through the user's
`FilteringRules`. Each rule is an AND of predicates with a `TriFilter`
outcome (`Show / Demote / Hide`). First-match-wins; the default is `Show`.

The rule list is **user-editable** in Settings → Rules, serialised to
`~/Library/Application Support/KAltSwitch/config.json`. Seed rules with
stable `default-*` ids ship with sensible defaults — they cover the
common no-brainer cases (hide KAltSwitch itself, hide tiny windows, hide
CG-side menu/Dock/Spotlight overlays, hide hidden helpers).

**Predicate scoping by nullability.** AX-derived rows have *all* CG-only
fields null; CG-derived rows have *all* AX-only fields default-y (role
null, `isMinimized`/`isFullscreen` false, etc.). The matcher resolves
predicates against null underlying values to `false` by convention. This
means a rule whose predicates include a CG predicate is automatically
**inert against AX rows** (the first CG predicate `false`s the AND), and
the reverse. Rule authors don't need an explicit source switch — they
write CG rules with CG predicates and AX rules with AX predicates.

**Two-pass classification.** First pass: every real window walks the rule
chain. Second pass: if an app's window list is entirely Hide-classified
*and* it has no surviving rows, the classifier synthesises a "windowless
app" stand-in window with default fields and re-walks the chain with
`isPhantom=true` (note: this `isPhantom` is the **windowless-app** kind,
not a CG-side cross-space row — those look like ordinary windows to the
rule layer). The `NoVisibleWindowsPredicate` is the only way to address
that second pass.

**Current-space mask.** After classification, `maskOffSpace(view,
visibleSpaceIds)` optionally forces any window whose `spaceIds` doesn't
overlap `visibleSpaceIds` to Hide. Controlled by the `currentSpaceOnly`
toggle in Settings; default `false` (show windows from every space).
Empty `spaceIds` = "no data" and the mask leaves the row alone.

---

## 6 — Presentation (switcher + inspector)

The classifier emits `FilteredSnapshot(show, demote, hide)` — three
buckets of `AppView`, each carrying its `WindowView` list with the
window-level `TriFilter`. The **switcher** consumes only `show` (and a
visual treatment for `demote`). The **inspector** renders all three.

`SwitcherController` selects the cursor's starting position from the
activation log, advances through `show` (and `demote` via cmd+→), and
fires `onCommitActivation(pid, windowId)` when the user releases cmd.

---

## 7 — Activation (commit path)

`macosApp/macosApp/AppRegistry.swift::commit(pid:windowId:)`

On commit we want to: (a) make the target app frontmost, (b) raise the
specific window the user picked, (c) flip the screen to the target
window's Space if it isn't currently visible. The full sequence:

1. **Resolve `CGWindowID`** for the target via a 3-tier chain:
   1. `watcher.cgWindowId(forAxWindowId:)` — AX-side, via the live
      `AXUIElement` in `windowsByHash` + `_AXUIElementGetWindow`.
      Works for windows AX currently sees.
   2. `phantomCgWindowId(pid, windowId)` — the
      `CGWindowListWatcher.cgWindowIdByPidByPhantomId` map keyed by
      cgwid-as-id (CG-only entries use their cgwid as Window.id).
   3. `store.cgWindowIdFor(pid, windowId)` — the final fallback. Reads
      the `Window.cgWindowId` field from the unified store. Covers the
      cross-source case: a window AX discovered (so its `id` is an
      AX-CFHash), AX later retracted on a Space change, the store still
      holds the entry with `sources={CG}` and the original cgwid intact.
2. **Decide cross-Space switch.** `swipeToSpaceFor(cgWindowId:)`
   compares the target's `spaceIdsFor(...)` against
   `currentVisibleSpaceIds()`. If the window's Space is already
   visible, no swipe; activation proceeds synchronously. Otherwise:
   - Find the owning display via `displayIdentifierForSpace`.
   - Compute `delta = targetIndex - currentIndex` in that display's
     `Spaces` array.
   - Hand `(count = |delta|, rightward = delta > 0,
     displayBounds = CGDisplayBounds(...))` to a `SwipeOrchestrator`,
     return `true` so `commit` defers activation.

   `commit` bumps `commitToken` and stashes the activation closure;
   the orchestrator's completion checks the token and runs the
   closure only if it still matches (a superseding cmd+tab through
   another cross-Space target cancels the older pending activation).
3. **Dock-swipe sequence (cross-Space only).** `SwipeOrchestrator`:
   1. Posts one synthetic 3-finger Space-swipe via the private
      `kCGSEventDockControl` + `kIOHIDEventTypeDockSwipe` CGEvent
      fields, `CGEventPost(.cgSessionEventTap, ...)`. The Dock
      processes it as if a real trackpad gesture, runs the standard
      swoosh animation.
   2. If the cursor isn't on the target display, briefly warps it to
      that display's centre (Dock binds the swipe to whichever display
      has the cursor) and warps back to `saved + delta` immediately
      after — the cursor never visually leaves its starting position.
      Reference: `iss` and `InstantSpaceSwitcher` use the same private
      field indices.
   3. Listens for `NSWorkspace.activeSpaceDidChangeNotification` (the
      reliable "swoosh complete" signal), advances to the next swipe
      if `count > 1`, or fires the completion after an 80 ms settle.
      A 250 ms per-swipe safety timeout fallback advances the sequence
      if the notification is ever dropped.

   `CGSManagedDisplaySetCurrentSpace` was the obvious-looking approach,
   but it's silently neutralised on recent macOS — flips an internal
   pointer without animating and pulls target-Space windows onto the
   current Space instead of switching. alt-tab-macos documents the
   same finding in their `experimentations/PrivateApis.swift`. The
   synthetic dock-swipe is the workaround.
4. **`bringAppToFront(pid, cgWindowId)`** — `SkyLight.swift`. Calls the
   private `_SLPSSetFrontProcessWithOptions(psn, cgWindowId,
   .userGenerated)` plus a Hammerspoon-style byte-record event to nail
   key-window status to *this* CGWindowID rather than the previous
   focused one in the same process.
5. **`watcher.makeWindowMain(windowId)`** — AX-only. Sets
   `kAXMainAttribute` + `kAXRaiseAction`. No-op for phantom commits.
6. **`NSRunningApplication.activate()`** — the free public fallback. If
   the private SkyLight call breaks in a future macOS, this keeps focus
   moving along the supported path.

**Race recovery.** `HotkeyController` samples
`NSEvent.modifierFlags.contains(.command)` inside its
`DispatchQueue.main.async` block and forwards it to
`SwitcherController.onShortcut(..., modifierHeld:)`. The CGEventTap
modifier-release dispatch and the Carbon-hotkey dispatch race onto
main with different latencies (~50 ms vs ~5–20 ms), so for sub-100 ms
cmd-holds the release used to land before the openSession and leave
the panel stuck until Esc. Now `onShortcut` checks `modifierHeld` on
the first-press branch and auto-commits on the default cursor if
cmd is already gone — same outcome as a properly-ordered
press/release pair.

---

## 8 — Cheat-sheet: where to look when…

| Symptom                                                           | Look here                                                                                                                   |
| ----------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| A window is *missing* from the switcher.                          | (a) AX trust? `[reg] AX trusted = …`. (b) Is the row in `[cgwl/emit]` for the latest refresh? (c) Is a rule hiding it?       |
| The switcher shows a row that *isn't* a real window.              | `[cgwl/emit]` for that pid → check `isOnscreen` / `cgLayer` / `ownerName` / size. Then write a rule to hide it.              |
| Off-space windows aren't showing up.                              | (a) `[cgwl/emit]` should have a row with `onscreen=0 onSpace=0`. (b) `currentSpaceOnly` setting must be off.                  |
| Cmd-tab to an off-space row activates the wrong window.           | `AppRegistry.commit` log: `[reg] cgwid-resolve` should land non-nil from one of `fromAx`/`fromPhantom`/`fromStore`. If all nil → the store has no cgwid for that windowId. |
| Cmd-tab to off-space target doesn't flip the Space (window pulled). | `[space-swipe]` should log `posting N swipe(s)` followed by `activeSpaceDidChange → visible now=[...]`. No `[space-swipe]` line → `spaceIdsFor` returned empty or the target Space is already visible. |
| Same window appears twice.                                        | One of the sources doesn't have `cgWindowId` populated → merge can't dedup. Check `_AXUIElementGetWindow` return path.       |
| All apps appear in the inspector but none in the switcher.        | Likely a rule with outcome `Hide` matching too broadly. Search `[ctl] openSession` output for the app set actually shipped. |

---

## 9 — Glossary

| Term                  | Meaning                                                                                                  |
| --------------------- | -------------------------------------------------------------------------------------------------------- |
| **AX**                | macOS Accessibility API. Public, requires user permission.                                                |
| **CG**                | CoreGraphics. `CGWindowListCopyWindowInfo` is public; the `CGS*` calls in `SkyLight.swift` are private.   |
| **CGWindowID**        | A `UInt32` the WindowServer uses to identify a window across the system. Stable for the window's lifetime. |
| **Phantom**           | A `Window` constructed from CGWindowList data, without a live AX element.                                |
| **Windowless-app phantom** | A *synthetic* `Window` the classifier synthesises when an app has no surviving rows — addressed only by `NoVisibleWindowsPredicate`. Unrelated to cross-space phantoms despite the name overlap. |
| **Space**             | A Mission Control desktop. Each connected display has its own active space at any moment.                |
| **`spaceIds`**        | Per-window list of Spaces the window currently belongs to. Empty = unknown.                              |
| **`isOnVisibleSpace`**| Boolean derived in Swift: any of `spaceIds` is in the current visible-space set across all displays.     |
