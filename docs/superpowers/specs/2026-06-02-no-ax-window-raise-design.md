# No-AX window raise via app activation fallback

**Date:** 2026-06-02
**Branch:** `no-ax-window-raise` (off `main`)
**Status:** design, awaiting implementation

## Problem

With Accessibility (AX) permission **not** granted, committing the switcher
brings the target window *input focus* (keyboard works) but does **not raise it
to the front** in z-order — the window stays visually behind other windows.
Because nothing visibly moves, it *looks* like the commit didn't happen, but it
did.

### What actually works without AX (verified from logs)

The commit itself fires fine without AX. In runs where AX was `false` for the
entire session, `[reg] commit pid=… cgs=ok axRaise=fail nsapp=ok` lines appear:

- **Commit trigger (cmd-release):** the overlay panel overrides `sendEvent`
  (`SwitcherOverlayWindow.swift:508`) and, with `canBecomeKey = true` (`:412`)
  and the panel made key on session start, receives `flagsChanged` through
  normal AppKit event delivery → calls `onCommandReleased` → `onModifierReleased`
  → commit. **No AX required.** (The off-main `CGEventTap` in `HotkeyController`
  is a *second* path that is AX-gated, but the panel-`sendEvent` path is the
  AX-free one and already covers the gesture.)
- **Input focus:** `bringAppToFront` → `_SLPSSetFrontProcessWithOptions(wid,
  userGenerated)` + `postKeyWindowEvent` (`SkyLight.swift:103/520`) make the
  *specific* target window key. Private CGS, no AX. This is why keyboard lands
  on the right window.

### What breaks: the z-order raise

The only missing step is the visual raise. In `AppRegistry.finishCommit`
(`AppRegistry.swift:410`):

- `makeWindowMain` → `kAXMain` + `kAXRaiseAction` (`AxAppWatcher.swift:603`) is
  the actual z-order lift, and it is **AX-only** — without AX it returns `false`
  (`axRaise=fail`).
- `NSRunningApplication.activate()` with no options (`AppRegistry.swift:422`)
  no-ops on macOS 14+ from a background non-active process (our case), so it
  does not perform the raise either.

Result: window gets focus but is never raised. Confirmed: no-AX commits log
`axRaise=fail`; AX commits log `axRaise=ok` and the window comes forward.

## Goal

Without AX, also bring the picked window to the front (z-order), so switching is
visually confirmed — not just focused.

## Approach

In `finishCommit`, when the AX raise did not happen (`makeWindowMain` returned
`false` — covers both "no AX" and phantom/cross-space windows with no live AX
element), perform an app-level raise via the **deprecated**
`NSRunningApplication.activate(options:)` with `.activateAllWindows`. Unlike the
option-less `activate()`, this older call raises the app's windows in z-order
and still functions from a background process. Public API, no AX.

When the AX raise succeeded, behavior is unchanged (keep plain `.activate()`),
so there is no regression for the normal AX-granted case.

### Concrete change

`AppRegistry.finishCommit` (around `:413-422`):

- Keep `cgsOk = bringAppToFront(...)` and `axOk = makeWindowMain(...)` as-is.
- Replace the unconditional `.activate()` with:
  - `axOk` → plain `.activate()` (unchanged), else
  - `.activate(options: [.activateAllWindows])` (the no-AX raise).
- Extend the commit log line (`:423`) to record which activation path ran and
  its result, so a no-AX run is debuggable from logs alone.

### Per-window precision — confirmed impossible without AX

Tested on a real run: without AX we **cannot** select a specific window of a
multi-window app. `.activateAllWindows` raises the app's windows and lets the
app pick its own main window (the last-active one); keyboard focus lands there,
not on the user's pick. Re-asserting the SLPS front-switch *after* activation
was tried and made it worse — the app stopped coming forward at all (reverted in
`e81c3f4`). There is no non-AX cross-process API to raise/focus one particular
window (`kAXRaiseAction` is AX-only); we do not pursue click-synthesis or code
injection. Additionally, the app does not receive a true activation
(`applicationDidBecomeActive`) without AX — input is routed to it but it isn't
the NSWorkspace-active app until it activates itself. Both are the macOS no-AX
ceiling, documented in code at `AppRegistry.finishCommit`.

### App-only switcher mode (the compensation)

Because per-window targeting is impossible without AX, the switcher drops to
**app-only mode** when `axTrusted == false`:

- **cmd+` — native outside a session, commit inside one.** Without AX we own
  cmd+` *only for the duration of a switcher session* (`setOverlayActive` calls
  `applyGraveOwnership(own:)` with the session-active flag). Outside a session
  the system "key above tab" hot key stays enabled, so cmd+` is macOS's native
  window-cycle. While a session is open we disable the system hot key and
  register our Carbon handler; pressing cmd+` then **commits the selected app**
  (`SwitcherController.onShortcut` → `commit` when `appOnly && entry == Window`)
  and ends the session, which restores the native hot key. The user's next
  cmd+` (cmd still held) then cycles the just-activated app's windows natively.
  This is the "commit-only" choice — we deliberately do **not** synthesize a
  forwarded cmd+` (a single-gesture commit+cycle), which would require toggling
  the symbolic hot key under a held key and racing the user's repeats. With AX
  we own cmd+` permanently (full window-stepping). cmd+tab is unaffected — it
  stays ours in all states (app-switching needs no AX).
- **No window stepping (defense in depth).** `SwitcherController.navigate` also
  makes `NextWindow` / `PrevWindow` a no-op when AX is off, so any window-step
  that still reaches the controller (e.g. arrow up/down inside our overlay)
  doesn't move the cursor. Only app-stepping moves it.
- **No per-window highlight.** `SwitcherOverlay` passes a null highlighted-window
  id when `!axTrusted`, so every window row's `isActive` is false. The app-cell
  outline (driven by `cursor.appIndex`) stays. Window rows remain visible, just
  unhighlighted.
- **App-level commit.** `SwitcherController.commit` passes `windowId = null` when
  AX is off, routing the platform layer to app activation
  (`SLPSMode.allWindows`) and keeping the recency log from recording a window the
  user never actually chose.
- **No per-Space filtering — show all apps.** Without AX the switcher shows
  every app regardless of which Space its windows are on. `SwitcherController`
  folds `axTrusted` into the current-space toggle (`currentSpaceOnly && axTrusted`)
  at both snapshot sites (`openSession` and the live `launchSnapshotCollector`
  combine), so `maskOffSpace` is disabled whenever AX is absent. Rationale: with
  no AX we can't focus a specific window or page Spaces, so per-Space filtering
  buys nothing; and the CG-only list has no AX event stream to refresh it
  promptly on a Space change (the one reactive CG `refresh()` can be coalesced
  away, leaving a ≤2 s lag). Showing all apps sidesteps that entirely and keeps
  every app reachable. Restored to the user's `currentSpaceOnly` setting when AX
  is granted.
- **No manual Space paging.** Because the commit carries `windowId = null`,
  `AppRegistry.commit` resolves no `cgWid` and therefore skips `swipeToSpaceFor`
  — the synthetic dock-swipe Space navigation is never engaged without AX. We
  rely on app activation: macOS switches to a Space with the app's open windows
  automatically (governed by the Mission Control setting "When switching to an
  application, switch to a Space with open windows"). So without AX we need
  neither window selection nor manual Space paging — plain app activation
  covers both. (If that setting is off, macOS may surface a window on the
  current Space instead of switching; acceptable, and the no-AX ceiling.)

## Verification

Build, revoke AX, run, confirm from a real run + logs:

1. **Raise:** commit onto an off-app window (AppKit app, e.g. Finder/TextEdit) →
   window comes to front. Note multi-window and non-AppKit behavior honestly.
2. **No regression with AX:** grant AX → `axRaise=ok` path unchanged, plain
   `.activate()` still used.

### Open empirical risks (resolved by verification, not design)

- Whether `activate(options: .activateAllWindows)` actually raises from
  background on this macOS (26 / Darwin 25.5). If it no-ops, revisit (candidate:
  also pass `.activateIgnoringOtherApps`, or re-issue the SLPS front-switch after
  the key-window events).
- `NSRunningApplication.activate(options:)` is deprecated as of macOS 14; we use
  it deliberately. Confirm the build does not treat the deprecation warning as
  an error (or localize a suppression).

## Out of scope

- **Commit trigger without AX — already works** via the panel `sendEvent` path;
  no change needed. (Earlier draft proposed a `CGEventSource.flagsState` poll;
  dropped as redundant after log evidence showed commits fire without AX.)
- Forcing a *specific* window to the front without AX (no viable non-injection
  path) — the switcher drops to app-only mode instead.
- True cross-app activation (`applicationDidBecomeActive`) without AX — macOS
  ceiling.
- Hiding window rows in app-only mode (kept visible, just unhighlighted).
- Tags and window-actions (cmd+M etc.) in app-only mode — already require a
  selected window; effectively inert without one. Not explicitly gated.
- Gating the switcher behind AX (rejected by the user).
