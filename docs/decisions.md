# Decisions log

Decisions made during MVP that aren't obvious from the code. Each entry has
**Question / Sources / Decision / Confidence / Revisit-trigger**. The goal is
that anyone (future me, future you) reading the codebase can quickly
understand *why* a piece works the way it does, and what would invalidate
that choice.

For the longer-form research that fed many of these (per-window/per-app
attributes, AX threading model, post-MVP roadmap) see
[`window-state-attributes.md`](window-state-attributes.md).

---

## 1. Kotlin/Native + Swift host instead of pure Kotlin/cinterop

**Question.** PLAN.md envisaged a Kotlin/Native macosArm64 app where every
piece of macOS interop (Cocoa, AX, Carbon, NSPanel) is reached via
`cinterop`. Should we do that, or use a thin Swift host app that loads a
Compose framework?

**Sources.** Day-1 spike — cinterop bindings for `NSPanel`, `AXObserver`
callbacks, and Carbon `RegisterEventHotKey` were each materially uglier in
Kotlin than in Swift (required `@Convention(c)`-style trampolines, manual
`Unmanaged.passUnretained`, and explicit `@_silgen_name` for SkyLight private
APIs). Pattern in alt-tab-macos and AeroSpace: pure Swift host.

**Decision.** Two-process layout: Compose UI compiled as a static framework
(`ComposeAppMac`) consumed by a SwiftUI/AppKit shell under
`macosApp/`. Kotlin owns pure logic (state, switcher controller, store);
Swift owns AppKit, AX, Carbon, CGS. xcodegen + xcodebuild driven from
Gradle (`buildMacosApp`, `runMacosApp` tasks) — no hand-edited `.pbxproj`.

**Confidence.** High. The split has paid off — every "ugh, this is hard"
moment so far has been on the Swift side (CGS APIs, NSPanel timing,
sendEvent), where Swift's tooling and the alt-tab-macos / AeroSpace
references made fixes cheap.

**Revisit-trigger.** If JetBrains ships a clean Kotlin-native AppKit
binding equivalent to AppKit-from-Swift, or if the Swift side grows beyond
~1.5k LOC and starts hosting non-trivial logic that wants to be tested
with `kotlinx-test`.

---

## 2. SkyLight CGS for cross-process focus, not the AeroSpace agent-app pattern

**Question.** macOS 14+ silently no-ops `NSRunningApplication.activate(...)`
when the caller isn't the active app. Our overlay panel is
`.nonactivatingPanel` (so user input still reaches the previously-active
app), so the activate call always fails. Two ways out:

  a. Become an agent app (`LSUIElement = YES` in Info.plist), drop
     `nonactivatingPanel`, let `makeKey()` activate us, then `activate(...)`
     works.
  b. Keep nonactivating panel, use private SkyLight (`_SLPSSetFrontProcessWithOptions`
     + `SLPSPostEventRecordTo` + `_AXUIElementGetWindow`) to switch frontmost
     without our process becoming active.

**Sources.** alt-tab-macos uses (b). AeroSpace uses (a). Their reasoning is
encoded in their codebases — alt-tab-macos prioritizes a calmer UX during
selection; AeroSpace is happy to flicker because it's a tiling WM you launch
deliberately. See [`window-state-attributes.md`](window-state-attributes.md)
§8 for the trade-off matrix.

**Decision.** SkyLight (path b). Bindings live in
`macosApp/macosApp/SkyLight.swift`; the only consumer is `bringAppToFront`
inside `AppRegistry.commit`. SkyLight framework is linked via a manual
`-framework SkyLight` entry in `macosApp/project.yml` plus the system
private-frameworks search path.

**Confidence.** High *for now*. Pro: no flicker, no `applicationDidResignActive`
on the previously-active app, keystrokes still flow there during the
preview-hold window. Con: private API may stop working on a future macOS;
we accept that risk because alt-tab-macos has shipped on this path for
years.

**Revisit-trigger.** Any of: SkyLight binding fails on a beta macOS; user
reports of "switcher broken on macOS X.Y"; we want to drop private-API
dependencies for App Store distribution. The agent-app migration is fully
documented in `window-state-attributes.md` §8 — about a one-day port.

---

## 3. Carbon `RegisterEventHotKey` for global shortcuts, not `CGEventTap`

**Question.** Two ways to catch cmd+tab globally: Carbon's
`RegisterEventHotKey` (synchronous, no permission required) or a
`CGEventTap` on `kCGEventKeyDown` (filterable, but needs Accessibility).

**Sources.** PLAN.md §"System-shortcut hygiene"; alt-tab-macos uses
both — Carbon for the hotkey, CGEventTap only for the modifier-release.

**Decision.** Carbon for cmd+tab / cmd+shift+tab / cmd+\` / cmd+shift+\`
(four registrations, IDs 1–4, signature `'KALT'`). No AX permission needed
to register or fire, which means a freshly-installed copy works on first
launch without nag. Wired in `HotkeyController.swift`.

**Confidence.** High. Carbon hotkeys are torn down by the kernel on
process exit, so a normal exit or crash doesn't leave a stray
registration around.

**Revisit-trigger.** If we want chord shortcuts (e.g. cmd+option+tab) that
Carbon can't represent, or if Apple deprecates Carbon (rumoured every WWDC,
not happened yet).

---

## 4. Symbolic hotkey takeover via `CGSSetSymbolicHotKeyEnabled`

**Question.** Even with our Carbon registration, the system's *symbolic*
hotkey for cmd+tab still fires the macOS native switcher first — we lose
the race. How do we suppress the system's handling?

**Sources.** alt-tab-macos `SymbolicHotKeysHelper.swift`. Hammerspoon issue
\#370 documents the same trick.

**Decision.** Disable the symbolic hotkey IDs (1 = commandTab,
2 = commandShiftTab, 6 = commandKeyAboveTab) via the private CGS API
`CGSSetSymbolicHotKeyEnabled` at `applicationWillFinishLaunching`. Re-enable
them in `applicationWillTerminate` and in our SIGINT/SIGTERM/SIGHUP handler
(see decision #6). Bindings in `SkyLight.swift`.

**Confidence.** High. The toggle has worked unchanged for the same IDs since
at least macOS 10.7.

**Revisit-trigger.** macOS hides symbolic hotkey IDs (extremely unlikely;
breaks all third-party hot-key apps), or the IDs change.

**Caveat.** The disable persists across our process death. A crash with no
SIGTERM (e.g. SIGSEGV, force-quit, kernel panic) leaves the user without
system cmd+tab until they relaunch us. The `installSignalHandlers` path
covers SIGINT/SIGTERM/SIGHUP; SIGKILL and crashes can't be caught.
Documented user-facing fix: relaunch KAltSwitch.

---

## 5. `NSPanel.sendEvent` override for cmd-release detection (instead of relying solely on `CGEventTap`)

**Question.** `CGEventTap` on `flagsChanged` works only with Accessibility
permission. AX trust resets on every adhoc-codesigned rebuild, so the tap
silently dies during dev iteration. How to detect cmd-release without the
tap?

**Sources.** Trial-and-error during M3 — discovered that NSPanel's
`sendEvent` receives flagsChanged when the panel is key, *without* needing
AX. The `nonactivatingPanel` style allows the panel to be key without
activating the app, which preserves the "keystrokes reach the previously
active app" property we want.

**Decision.** Two parallel paths:
  - `SwitcherOverlayWindow` overrides `sendEvent` and watches for
    flagsChanged where `command` is no longer set → calls
    `controller.onModifierReleased()`. Always works.
  - `HotkeyController.installFlagsChangedTap()` keeps the CGEventTap as a
    fallback, on a userInteractive thread (alt-tab pattern — main-loop
    stalls otherwise trip the kernel watchdog and disable the tap).

The panel is made key from t=0 of every session (even during `showDelay`,
when `alphaValue == 0`) so sendEvent sees the release.

**Confidence.** High for the panel path. The CGEventTap fallback is now
mostly redundant but still handy when AX permission is granted and the user
releases cmd before the panel gets ordered front.

**Revisit-trigger.** If Apple changes `nonactivatingPanel` semantics so it
no longer receives flagsChanged when key.

---

## 6. Hot-key restoration on graceful exit and signals (but not on crash)

**Question.** Where does the lifecycle state — "system cmd+tab disabled" —
get restored?

**Sources.** alt-tab-macos `Sigtrap.swift`; macOS signal-source docs
(`DispatchSource.makeSignalSource`).

**Decision.** Three layers:
  1. `applicationWillTerminate` — normal Cocoa shutdown. Calls
     `setSymbolicHotKeysEnabled(true)` and `hotkeyController.stop()`.
  2. `installSignalHandlers` — catches SIGINT (Ctrl-C from Xcode console),
     SIGTERM (`kill <pid>`, logout, system shutdown), and SIGHUP. Pattern:
     `signal(sig, SIG_IGN)` to ignore default action, then a
     `DispatchSourceSignal` on the main queue runs the same teardown and
     `exit(0)`. Safe to call CGS APIs because the dispatch source fires
     off the signal context.
  3. *Nothing* covers SIGKILL, SIGSEGV, force-quit, kernel panic. The user
     loses system cmd+tab until next launch. Mitigation: the symbolic hotkey
     re-enable runs on every launch *after* we re-disable it, so a single
     `open -a KAltSwitch` followed by a normal quit fixes the user's state.

**Confidence.** High for layers 1+2. Layer 3 is documented and accepted —
no software-only fix exists.

**Revisit-trigger.** User reports of "system cmd+tab gone after KAltSwitch
crash" — surface a one-shot launchd-style helper that re-enables the hotkey
on the next user session.

---

## 7. Single activation log, not two LRU lists

**Question.** Original design had separate "app order" and "window-within-app
order" LRU lists. Both update on activation events; keeping them consistent
across drops, dedupe, and previews was getting messy.

**Sources.** Refactor in `9cbc12f` / `366d6d9` / `5cf5b81` (see git log).
Inspector showed apps and windows out-of-sync after preview-raise on a few
edge cases.

**Decision.** One ordered list of `ActivationEvent(pid, windowId?)` in
`ActivationLog`. App order = walk newest-first, emit each pid once. Window
order within an app = same, filtered by pid, with non-null windowIds only.
This makes "moving an app to front via window activation" and "moving a
window to front via app activation" automatically consistent. The only
mutator is `WorldStore.recordActivation(pid, windowId?)`, gated by
`switcherActive`.

**Confidence.** High. Works in inspector and switcher with one mental
model. No `timestampMs` needed — insertion order is the timeline.

**Revisit-trigger.** If we ever need wall-clock duration between activations
(e.g. for a "you've been in this app for X minutes" feature), put time back
on the event but keep ordering insertion-driven.

---

## 8. Synchronous activation recording in `commit`, not waiting for AX/Workspace echo

**Question.** When the user commits a switch (cmd-release), the OS *should*
fire `kAXFocusedWindowChanged` and/or `didActivateApplicationNotification`,
which our watchers convert to `recordActivation`. Why also record
synchronously inside `commit()`?

**Sources.** Manual testing on Settings.app and Firefox: focus transferred
correctly (CGS-driven), but the AX echo was either delayed by hundreds of ms
or never arrived at all (esp. window-only switches inside one app — macOS
doesn't fire focused-window-changed for those).

**Decision.** `SwitcherController.commit()` calls
`store.recordActivation(app.pid, window?.id)` *before* invoking
`onCommitActivation` (the Swift-side AX/CGS path). Order in
`AppRegistry.commit` is CGS-first, AX-last (per alt-tab pattern).
`switcherActive` is cleared synchronously when the session closes, so any
delayed AX echo lands in the log naturally and dedupes via
`appOrder()`'s newest-first walk.

**Confidence.** High. Inspector's row order moves on every commit now,
regardless of whether the OS fires its echo.

**Revisit-trigger.** If we add a second activation source (e.g.
NSWorkspace mouse-driven switches) and need cleaner attribution.

---

## 9. Preview-raise deferred to post-MVP

**Question.** PLAN.md §"Switcher activation timing" specs a `previewDelay`
(default 250 ms) after which the cursor's window is AX-raised behind the
switcher panel as a real-window preview. Why is `previewEnabled = false`
by default?

**Sources.** `window-state-attributes.md` §8 captures the long-form
reasoning. Short: `kAXRaiseAction` reorders other windows in unwanted ways
without a window-level z-order trick to keep our panel above the raised
window, and `switcherActive`'s synchronous-clear timing leaks the preview's
own AX echo back into the log on slow apps.

**Decision.** `SwitcherSettings.previewEnabled` defaults to `false`. The
inspector's Settings panel has a toggle so users can opt in. The full code
path (`schedulePreview` → `onRaiseWindow` → `AxAppWatcher.raiseWindow` →
`kAXRaiseAction`) stays compiled and tested via `previewRaise_*` tests in
`SwitcherControllerTest`.

**Confidence.** High that the call to defer is right for v1. Medium on
which fix is the right one when we re-enable — z-order trick vs. extended
debounce vs. CGS-driven raise instead of AX.

**Revisit-trigger.** First user feedback session, or when we dogfood the
switcher long enough to build intuition for what "preview" should *feel*
like.

---

## 10. Leaf platform-glue lives in Kotlin, lifecycle orchestration stays in Swift

**Question.** §1 fixed the high-level split (Swift host shell hosting a
Kotlin/Native Compose framework). But the boundary between Swift and
Kotlin within that split was set tactically rather than principle-first
— several pieces ended up on the Swift side because the day-1 spike
landed there, not because they had a structural reason to be in Swift.
What's the principle?

**Sources.** Iter1–4 cleanup pass. Specifically: stderr-to-log redirect
(POSIX), `NSColor.controlAccentColor` → 0xRRGGBB packing, the
`NSImage` → 128×128 PNG icon path, and the `pushAppRecord` per-pid
field translation each lived in Swift purely because that was where
they got first written. None of them touched anything that's
materially uglier in K/N than Swift — they're all leaf functions
calling Foundation/AppKit/CoreGraphics public API.

**Decision.** Leaf utilities — pure functions, finite-call subscriptions,
no AppKit lifecycle responsibility — belong in `sharedUI/.../native/`
on the Kotlin side. The Swift host keeps responsibilities that are
materially uglier in K/N: AppKit `@main` + `NSApplicationDelegate`
boilerplate, AppKit window subclassing (`NSPanel.sendEvent` override
in `SwitcherOverlayWindow`), `AXObserver` C-callback trampolines and
the dedicated CFRunLoop thread, Carbon `RegisterEventHotKey` C-callback
trampolines and the `CGEventTap`'s userInteractive-thread CFRunLoop,
`@_silgen_name` private-framework references for SkyLight.

After iter1–4 the Kotlin side owns: log formatter, stderr redirect,
icon PNG conversion, system-accent observation, per-pid record upsert,
config persistence, AX-trust prompt. The Swift side owns: AppDelegate
lifecycle (~570 LOC), AppRegistry (workspace observers + AX-trust poll
+ space changes), AxAppWatcher (per-app AX observer thread),
HotkeyController (Carbon + CGEventTap), SwitcherOverlayWindow (NSPanel
subclass), SkyLight (private bindings), Log (3-line shim).

**Confidence.** High. Each move during iter1–4 reduced Swift→Kotlin
boundary surface (one function call instead of N argument crossings),
removed duplicated logic (the timestamp formatter; the byte-by-byte
KotlinByteArray copy in `pushAppIcon`), and didn't trip on K/N AppKit
binding gaps that wouldn't have a clean workaround.

**Revisit-trigger.** A K/N release that lands clean ObjC subclassing
support (lets us host `NSPanel` from Kotlin without
`@Convention(c)`-style trampolines) — at that point the panel + AX
observers become candidates. Or if the Swift side grows beyond
~1.5k LOC and starts hosting non-trivial logic that's hard to test
from `kotlinx-test`.

**Caveats / known K/N AppKit binding gaps documented during iter3–4.**
These took non-trivial detective work the first time; capturing them
here saves the next contributor.

  - `NSCompositingOperationCopy` (the post-10.14 enum case) is **not**
    exposed by the K/N AppKit binding; the deprecated
    `NSCompositeCopy` ULong constant *is*. Used in `IconLoader.kt`.
  - `NSBitmapImageFileTypePNG` is **not** at the package top level for
    the same reason; the legacy `NSPNGFileType` ULong is. Used in
    `IconLoader.kt`.
  - `NSBitmapImageRep.representationUsingType:properties:` is exposed
    as a *top-level extension* on `NSBitmapImageRep`, not a member
    method — requires an explicit
    `import platform.AppKit.representationUsingType` to resolve.
  - `NSApplicationActivationPolicy` is a strict CEnum (rather than a
    ULong typealias with top-level constants), so cases must be
    qualified:
    `NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular`.
    Used in `AppRecord.kt`.
  - `NSDate.timeIntervalSince1970` is a top-level extension *function*,
    not a property — needs explicit import + `()` at the call site.
  - `NSLog(format, vararg)` does **not** reliably bridge Kotlin String
    through C-style `%@` varargs. The K/N→ObjC arg conversion produces
    a non-NSObject pointer that crashes in
    `_NSDescriptionWithStringProxyFunc` / `objc_opt_respondsToSelector`.
    See the iter1 commit (`StderrRedirect.kt`) for the detection +
    fix; in general, prefer `println` for K/N diagnostic output.

---

## 11. Single mutable `WorldStore` fronted by `MutableStateFlow`, not Compose-state-only

**Question.** PLAN.md envisaged the activation log etc. living on the
Compose side as plain runtime state. But our writers are *not*
Compose: they're the Swift AX/Workspace observers + the Kotlin
SwitcherController commit path. How do those producers feed the UI
without the UI being the source of truth?

**Sources.** Native code can't drive Compose recomposition directly —
recomposition happens inside the Compose runtime, which the Swift
side doesn't know exists. Need a producer-thread-safe boundary that
both Swift and Compose can observe. `MutableStateFlow` fits both:
thread-safe atomic mutators (called from Swift main and AX threads
through the framework boundary), and `collectAsState()` for the
Compose UI. Equality conflation is built-in.

**Decision.** Single `WorldStore` singleton, exposed via the framework
as `ComposeViewKt.store`. Every piece of mutable global state lives
in there as a `MutableStateFlow<T>`; mutators are public methods.
Compose UI uses `collectAsState()`. The Swift side both writes
(`recordActivation`, `setWindows`, etc.) and reads (`store.windowFrame
.value`, `store.inspectorVisible.value`) directly through the
framework export.

**Confidence.** High. The pattern carried iter1–5 cleanly; the
contract is now pinned by `WorldStoreTest`. Re-reads from Swift via
`store.<field>.value` work, are thread-safe, and don't require any
`KotlinNativeFreezeAfterCompose` workarounds (Kotlin/Native's
new-mm doesn't need them).

**Revisit-trigger.** If we ever need Compose-driven derived state to
flow *back* to the Swift host beyond the existing
`observe*` bridges, consider exporting the StateFlows directly to
Swift (Kotlin/Native can produce ObjC-callable `Cancellable` adapters)
rather than growing the bespoke-callback bridge.

---

## 12. Bounded `ActivationLog` + runtime-id pruning at the WorldStore boundary

**Question.** `ActivationLog.events` was append-only, and `WorldStore`
removal paths (`removeApp`, `setWindows`) didn't prune the log. Two
problems:

  1. KAltSwitch is a long-running menubar app. AX and NSWorkspace can
     each emit the same activation (we record synchronously in
     `SwitcherController.commit`, the OS may echo later). With nothing
     evicting old events, memory grows linearly forever.
  2. Pids and AX window ids are macOS-runtime ids that get reused.
     A future unrelated launch could inherit dead-ancestor recency just
     by happening to take the same pid.

**Sources.** Cross-branch review of `codex` iterations 1–3 + 9. The
`codex` branch independently reached the same conclusion working from
a slightly earlier base.

**Decision.** Three changes, all in commonMain so they're testable:

  * Bound `ActivationLog` at `DefaultMaxActivationEvents = 2_048`. The
    cap is memory-only — `appOrder()` / `windowOrder()` already project
    older duplicates away.
  * `record(event)` collapses adjacent-equal events. Catches the AX echo
    immediately after our own synchronous commit.
  * Add `withoutPid` / `withoutWindow` / `withoutMissingWindows` log
    helpers and call them from `WorldStore.removeApp` and
    `WorldStore.setWindows`. Active-pointer pointers (`activeAppPid`,
    `activeWindowId`) clear if the thing they pointed at disappears,
    but the active *app* pointer survives a sub-window vanishing.
  * `setWindows` recursively collects every live id, including child
    windows reported under `Window.children`, so attached
    sheets/drawers/popovers don't get their events pruned.

**Confidence.** High. The contract is pinned by 6 new
`ActivationLogTest` cases + 4 new `WorldStoreTest` cases.

**Revisit-trigger.** If a future feature needs long-term focus history
(analytics, dwell-time reporting), promote the cap to a configurable
constant with a separate "audit log" if needed — but keep `appOrder` /
`windowOrder` driven by the bounded recency view either way.

---

## 13. Settings/Inspector frame math lives in commonMain Kotlin

**Question.** The arithmetic translating `WindowFrame.width =
settings-only width` to/from the live `NSWindow.frame` was inline in
`macosAppApp.swift`. It's pure math, but it touches a persisted-model
contract (`AppConfig`/`WindowFrame` semantics). Where should it live?

**Sources.** Cross-branch review of `codex`'s independent
`InspectorWindowLayout.kt` work. Same reasoning the project's iter1–4
applied to leaf platform-glue (decisions.md §10): if it's pure math
that touches a persisted contract, the contract owner is Kotlin.

**Decision.** Three pure functions in
`commonMain/.../config/InspectorWindowLayout.kt`:
`restoredInspectorWindowFrame`, `persistInspectorWindowLayout`,
`inspectorVisibilityTargetFrame`. Each maps live frame ↔ persisted
shape. Swift keeps `NSWindow.frame` access and provides tiny
`NSRect` ↔ `WindowFrame` adapters; everything else is one Kotlin call
per location.

**Confidence.** High. 7 commonTest cases cover restore-with-and-without
inspector, persist with width clamps, toggle with min-settings clamp,
and the no-saved-frame first-launch path.

**Revisit-trigger.** If inspector layout becomes platform-specific in
a way that can't be expressed as pure frame math (e.g. stage-manager
adaptations on iPadOS-style sidebars).

