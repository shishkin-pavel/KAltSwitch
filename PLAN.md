# KAltSwitch — Compose Multiplatform alt-tab alternative for macOS

## Context

`KAltSwitch` is currently an empty Compose Multiplatform JVM-desktop scaffold:
two modules (`desktopApp`, `sharedUI`) and two real `.kt` files — `main.kt`
opens an 800×600 window and `App.kt` renders `Text("hi!")`. Versions:
Compose Multiplatform 1.10.3, Kotlin 2.3.20, JVM 17. There is no
macOS-native interop wired in.

The goal is to turn this scaffold into a macOS alt-tab replacement, **fully in
Compose Multiplatform** — no JVM, no Java. Small bits of **Swift/Objective-C
glue are allowed** where they make interop materially simpler than going
straight from Kotlin via `cinterop` (e.g. APIs that are awkward to bind from
C-headers, Swift-only frameworks). Default path is still pure Kotlin.

### Feature spec

- **App-centric layout**: horizontal row of app icons, ordered left-to-right
  by activation recency. Window titles listed vertically beneath each icon.
  **Apps with zero windows** appear at the **end of the row, after a
  vertical separator** — they are still selectable but never the default.
- **Two shortcuts** (entry):
  - `cmd+tab` — opens switcher with cursor on **app[1].window[0]**
    (second-most-recent app, its most-recent window).
  - `cmd+\`` — opens switcher with cursor on **app[0].window[1]**
    (current app, its second-most-recent window).
- **Held-modifier semantics**: switcher is shown only if the modifier (cmd) is
  held longer than `showDelay` (default **20 ms**, configurable). If released
  faster, no UI is shown — we just switch to the cursor's default target. This
  preserves the "just tap cmd+tab to flip" muscle memory.
- **Navigation while open**:
  - `tab` / `shift+tab` ≡ `→` / `←` — move between **apps** in visual order
    (= activation recency, with windowless apps after the separator).
  - `\`` / `shift+\`` ≡ `↓` / `↑` — move between **windows** of the focused
    app, again in visual (recency) order.
- **Esc** cancels (no activation, history untouched). Releasing cmd commits.
- **No live thumbnails**. Instead, when the cursor settles on a window for
  longer than `previewDelay` (default **250 ms**, configurable), that window
  is raised behind the switcher panel as a real-window preview. The
  previewing-raise must **not** pollute the activation history; only the
  final commit on cmd-release does.
- **Group abstraction** lives in the model from day 1 but only the trivial
  `AppGroup(app)` impl exists. cmd+\` operates on the current group. Adding
  cross-app user-defined groups later is purely additive — no refactor.

## Reference research & decisions log

The reference apps in `~/projects/alt-tab-macos` and `~/projects/AeroSpace`
must be studied — not copied — for the system-interaction patterns that are
hard to discover from Apple docs alone:

- Window/app data acquisition (AX, NSWorkspace, optionally SkyLight).
- Focus switching that doesn't fight the system.
- Global shortcut registration at startup.
- Shortcut restoration on **normal exit and crash** (see "System-shortcut
  hygiene" below).
- Edge cases: Stage Manager, Spaces, fullscreen apps, Mission Control,
  multiple displays.

Findings and the decisions they lead to are recorded in a separate file,
**`docs/decisions.md`**, written alongside the code. Each decision entry has:

- **Question** — what we needed to decide.
- **Sources** — file paths in reference projects, links, Apple docs.
- **Decision** — what we chose and why.
- **Confidence** / **revisit-trigger** — when to reopen this decision.

The first three entries to write up front:

1. AX-only vs. SkyLight for window enumeration (and the cross-space toggle).
2. Carbon `RegisterEventHotKey` vs. `CGEventTap` for the global hotkey.
3. Hot-key restoration on crash.

## Architecture

### Runtime: Kotlin/Native macosArm64 + Compose MP native target

We move the project off JVM-desktop. `cinterop` binds Cocoa, AX, Carbon,
CoreGraphics directly from Kotlin. **Reverting to a JVM-based stack is a
non-goal** — if the day-1 spike hits Compose-on-native rendering issues, we
work around them (small Swift wrappers, file bug reports against
JetBrains/Compose, custom Skiko hosting) rather than retreating to JVM.

### Module layout

```
KAltSwitch/
├── shared/                  (renamed from sharedUI)
│   └── commonMain/          — pure-Kotlin domain logic
│       App, Window, Group, ActivationLog, SwitcherState, SwitcherEvent
├── macos/                   (new, replaces desktopApp)
│   ├── nativeInterop/cinterop/
│   │   ax.def, carbon.def, appkit_extras.def
│   ├── swift/               — optional Swift glue files (.swift) compiled
│   │                          into a static lib and cinterop'd in. Used
│   │                          only where Kotlin cinterop is materially
│   │                          uglier than Swift.
│   ├── nativeMain/kotlin/.../native/
│   │   Ax.kt, Workspace.kt, Hotkeys.kt, EventTap.kt, IconLoader.kt,
│   │   PanelWindow.kt, ReopenHandler.kt
│   └── nativeMain/kotlin/.../ui/
│       SwitcherPanel.kt, AppCell.kt, WindowList.kt, SettingsWindow.kt
└── docs/
    └── decisions.md         — research + decisions log
```

### Native bindings (cinterop)

| Native API | Used for |
|---|---|
| `AXUIElement` (ApplicationServices) | enumerate per-app windows; raise without focus (`kAXRaiseAction`); commit focus (`kAXMainAttribute`) |
| `AXObserver` | per-app subscriptions: `kAXFocusedWindowChangedNotification`, `kAXWindowCreated/Destroyed`, `kAXTitleChanged` |
| `NSWorkspace` (AppKit) | running apps; `didLaunch/Terminate/ActivateApplicationNotification` |
| `NSRunningApplication` | bundle id, `activationPolicy`, app icon (`icon.tiffRepresentation`) |
| `RegisterEventHotKey` (Carbon) | global cmd+tab and cmd+\` registration. Does **not** require AX permission. |
| `CGEventTap` on `kCGEventFlagsChanged` | detect cmd release to commit; needs AX permission |
| `NSPanel` + `NSWindow.level = .popUpMenu` | borderless, transparent, non-activating Compose panel |
| `NSApplicationDelegate.applicationShouldHandleReopen:hasVisibleWindows:` | re-launch via Spotlight opens the settings window |
| _(behind cross-space flag)_ `CGSCopyWindowsWithOptionsAndTags` | private SkyLight; isolated cinterop module, off by default |

### Activation log (single source of truth)

Replaces the earlier two-LRU-list design. We store **one ordered event list**:

```kotlin
data class ActivationEvent(val instant: Instant, val pid: Int, val windowId: CGWindowID)

class ActivationLog {
    private val events: ArrayDeque<ActivationEvent>  // newest first

    fun appOrder(): List<App>            // unique pids in newest-first order
    fun windowOrder(app: App): List<Window>  // events filtered by pid, unique windows newest-first
    fun record(event: ActivationEvent)
}
```

- **App order** is derived: walk events newest-first, emit each pid the first
  time it appears. Apps that have no events but are running are appended at
  the end (these are also the windowless apps, since "having a window" means
  some AX focus event has been seen for them).
- **Window order within an app**: same projection, filtered by pid.
- This guarantees "moving an app to front via window activation" and "moving
  a window to front via app activation" are consistent automatically — they
  share one timeline.
- Mutated only by:
  - `kAXFocusedWindowChangedNotification` per-app AX observer →
    `record(ActivationEvent(now, pid, winId))`.
  - `NSWorkspace.didActivateApplicationNotification` for apps with no current
    AX-known window → `record(ActivationEvent(now, pid, NoWindow))`.
- A single in-flight flag `isPreviewing` is set while the switcher panel is
  visible. AX/Workspace events arriving during that window are dropped (they
  describe our own preview-raises, not user intent).

### Raising a window without polluting history

- **Preview raise** (after `previewDelay` of stable selection):
  `AXUIElementPerformAction(window, kAXRaiseAction)` — raises without making
  the app key, doesn't fire `didActivateApplicationNotification`.
- **Commit** (cmd-release):
  `[NSRunningApplication activateWithOptions:.activateIgnoringOtherApps]` +
  `AXUIElementSetAttributeValue(window, kAXMainAttribute, true)` +
  `kAXRaiseAction`. *Intended* to update history.

### Switcher activation timing

Timeline from `cmd+tab` (or `cmd+\``) press to release:

```
t=0     hotkey fires; resolve default cursor; arm two timers
t<20ms  cmd released  → no UI, commit immediately, done
t=20ms  showDelay fires → render panel, isPreviewing=true
        (user navigates, possibly rapidly)
t=Δ+250 previewDelay (resets each selection change) → AX-raise
t=tR    cmd released   → if no UI was shown, commit cursor;
                          else commit current selection;
                          isPreviewing=false; flush event buffer
```

Both delays live in settings (`showDelay`, `previewDelay`).

### Switcher state machine

One state machine, two entry shortcuts that differ only in default cursor:

- `cmd+tab` → cursor at `app[1].window[0]` (second-most-recent app).
- `cmd+\`` → cursor at `app[0].window[1]` (current app, second window).

While open, all keys are equivalent: tab/shift-tab and `→`/`←` move apps;
`\``/`shift+\`` and `↓`/`↑` move windows. Esc cancels. Cmd-release commits.

### UI

- Borderless transparent `NSPanel`, non-activating. Compose renders into it.
- Horizontal row of app cells: 64×64 icon, truncated app name, vertical list
  of window titles below.
- A **vertical separator** in the row marks the boundary between apps with
  windows (left, recency-ordered) and apps without windows (right). The
  separator is purely visual; navigation skips it without stopping.
- Selected cell/window visualized with border + tint. Window list scrolls
  if long.
- App icons via `IconLoader.kt`: `NSRunningApplication.icon` →
  `tiffRepresentation` → bytes → Compose `ImageBitmap`, cached by bundle id.

### Settings

- Compose settings window. Two ways to open it:
  1. Menubar `NSStatusItem` (default visible; toggleable).
  2. **Re-opening the running app** (Spotlight / Dock / `open -a`). Handled
     via `applicationShouldHandleReopen:hasVisibleWindows:` returning false
     while we instead surface our settings window. This is the escape hatch
     for users who turn the menubar icon off.
- v1 settings:
  - **Menubar icon visible** (default `true`).
  - **`showDelay`** — modifier-hold threshold before showing UI (default 20 ms).
  - **`previewDelay`** — selection-stability threshold before AX-raise
    preview (default 250 ms).
  - **Modifier key** for switcher (default `cmd`; `alt` allowed).
  - **Cross-space window enumeration** (default off; on enables the SkyLight
    cinterop module).
- Filtering toggles (hide minimized, hide hidden, hide other-space) are
  **deferred**. v1 shows everything; windowless apps just go behind the
  separator.
- Persisted as JSON via `kotlinx-serialization` at
  `~/Library/Application Support/KAltSwitch/config.json`.

### Initial activation log seed

On launch, walk `NSWorkspace.runningApplications` filtered to
`activationPolicy == .regular`, ordered as the system reports them, and emit
one `ActivationEvent` per app at descending timestamps. For the currently
frontmost app we additionally read its AX `kAXFocusedWindow` and record a
window-level event. This means cmd+tab is meaningful from the first
keystroke after launch.

### System-shortcut hygiene (registration & restoration)

- We register `cmd+tab` and `cmd+\`` via Carbon `RegisterEventHotKey`. Per
  Apple docs, these registrations are **per-process and torn down by the
  kernel on process exit**, including crashes — system defaults are
  automatically restored. Verify this in `docs/decisions.md` with a citation.
- The `CGEventTap` for `flagsChanged` is also process-scoped and released on
  exit.
- We still install a graceful-shutdown path:
  `NSApplicationWillTerminateNotification` and a `signal(SIGINT/SIGTERM)`
  handler that calls `UnregisterEventHotKey` explicitly — belt-and-braces.
- Document this in `docs/decisions.md` with the actual macOS behavior we
  observed during a test crash.

### Permissions & entitlements

- **Accessibility permission**: required. On launch, probe with
  `AXIsProcessTrustedWithOptions`; if false, show a Compose dialog with a
  button that opens System Settings → Privacy → Accessibility.
- **Screen Recording permission**: not required (no thumbnails).
- Entitlements: `com.apple.security.app-sandbox = false`,
  `com.apple.security.cs.disable-library-validation = true` (only needed
  when SkyLight cross-space is enabled).

## Critical files to modify or create

- `settings.gradle.kts` — replace `desktopApp` with `macos`; rename
  `sharedUI` → `shared`.
- `shared/build.gradle.kts` — add `macosArm64()` target; commonMain only.
- `shared/src/commonMain/kotlin/com/shish/kaltswitch/model/`
  — `App.kt`, `Window.kt`, `Group.kt`, `ActivationLog.kt`,
  `SwitcherState.kt`, `SwitcherEvent.kt`.
- `shared/src/commonTest/kotlin/.../ActivationLogTest.kt`,
  `SwitcherStateTest.kt`.
- `macos/build.gradle.kts` — Compose application for `macosArm64`,
  cinterop sourcesets, optional Swift compile target.
- `macos/src/nativeInterop/cinterop/*.def`.
- `macos/swift/*.swift` *(if needed)*.
- `macos/src/nativeMain/kotlin/.../native/*.kt` — `Ax.kt`, `Workspace.kt`,
  `Hotkeys.kt`, `EventTap.kt`, `IconLoader.kt`, `PanelWindow.kt`,
  `ReopenHandler.kt`.
- `macos/src/nativeMain/kotlin/.../ui/*.kt` — `SwitcherPanel.kt`,
  `AppCell.kt`, `WindowList.kt`, `SettingsWindow.kt`.
- `macos/src/nativeMain/kotlin/Main.kt`.
- `docs/decisions.md` — research + decisions log (see section above).

## Verification

- **Day-1 spike**: scaffold `macosArm64` with Compose, render `Text("hello")`
  in a borderless floating non-activating NSPanel above other windows.
  Compose-on-native issues are worked around, not used as an excuse to
  retreat to JVM.
- **Per-milestone manual checks**:
  1. AX observer fires on focus change → `ActivationLog` updates.
  2. cmd+tab held > `showDelay` → overlay appears at app[1].window[0].
  3. cmd+tab tapped < `showDelay` → no overlay, app[1].window[0] activates.
  4. Selection navigation: window stable for `previewDelay` → AX-raise.
     Esc cancels: history is byte-for-byte identical to before.
  5. cmd-release commits → history updates exactly once.
  6. cmd+\` → cursor at app[0].window[1].
  7. Spotlight-launch while running → settings window appears (menubar icon
     toggle has no effect on this path).
  8. Force-quit the app → cmd+tab returns to macOS native behavior.
- **Headless tests** (`shared` commonTest): single-list activation projections
  (app order, window-within-app order), default-cursor logic for both
  shortcuts, edge cases (single app, single window, all-windowless), the
  `Group` abstraction.

## Remaining open questions

1. **Swift glue scope**. You okayed Swift for "convenient interop". Concretely:
   should I write Swift wrappers proactively for any AX/AppKit surface that
   has a clean Swift form (e.g. async sequences, Sendable wrappers), or keep
   Swift strictly to "cinterop is impossible/very ugly" cases?
2. **`ActivationEvent` for windowless apps**. The single-list model needs a
   way to represent "this app was activated but we don't have a window id".
   Plan: a sentinel `windowId = NO_WINDOW`. Confirm that's acceptable, or
   prefer `ActivationEvent.windowId: CGWindowID?` (nullable) — equivalent
   semantics, slightly different ergonomics.
3. **Separator visual**. Is a thin vertical divider plus extra horizontal gap
   enough, or do you want windowless apps visually de-emphasized too (e.g.
   smaller icons, dimmed)?
4. **Settings window invocation when overlay is open**. If the user
   re-launches the app via Spotlight while holding cmd+tab, what wins —
   commit the switch first, then show settings, or ignore the reopen until
   the modifier is released?
