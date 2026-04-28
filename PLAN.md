# KAltSwitch — Compose Multiplatform alt-tab alternative for macOS

## Context

`KAltSwitch` is currently an empty Compose Multiplatform JVM-desktop scaffold:
two modules (`desktopApp`, `sharedUI`) and two real `.kt` files — `main.kt`
opens an 800×600 window and `App.kt` renders `Text("hi!")`. Versions:
Compose Multiplatform 1.10.3, Kotlin 2.3.20, JVM 17. There is no
macOS-native interop wired in.

The goal is to turn this scaffold into a macOS alt-tab replacement, **fully in
Compose Multiplatform** (no Swift, no Java sidecar). Reference apps live in
`~/projects/alt-tab-macos` (Swift, 18.7k LoC, uses private SkyLight + AX +
ScreenCaptureKit + Carbon) and `~/projects/AeroSpace` (Swift WM that gets by
with AX + NSWorkspace only — closer to what we need).

### Feature delta vs. alt-tab-macos

- **App-centric layout** (not window-centric). Horizontal row of app icons
  ordered left-to-right by activation recency; window titles listed vertically
  beneath each icon.
- **Two separate shortcuts**:
  - `cmd+tab` — cycles **apps** by activation recency. ←/→ or tab/shift-tab.
  - `cmd+\`` — cycles **windows of the current group** (group = single app for
    now; future: user-defined cross-app groups). ↑/↓ navigates inside a group;
    ←/→ stays usable for switching apps in either mode.
  - Switcher stays open while the modifier (cmd) is held; release commits,
    Esc cancels.
- **No live thumbnails.** Instead, when the cursor moves to a window in the
  switcher, that window is raised to front (behind the switcher panel) so the
  user sees the actual window. **Critical**: this previewing must not pollute
  the activation history — only the final commit on cmd-release does.
- **Group abstraction** is in the model from day 1 but only the trivial
  `AppGroup(app)` impl exists. The cross-app group feature is left as a
  stub-out so adding it later is purely additive.

## Architecture

### Runtime: Kotlin/Native macosArm64 + Compose MP native target

The project moves off JVM-desktop. We use `cinterop` to bind Cocoa, AX,
Carbon, CoreGraphics directly from Kotlin. No JNA, no Swift bridge.

**Risk**: Compose Multiplatform's native-macOS target is still maturing.
Day-1 spike must verify: a borderless transparent Compose window can be
floated above all apps with correct input focus. If this fails, fall back to
**Plan B** (see Open Questions §1).

### Module layout

```
KAltSwitch/
├── shared/                  (renamed from sharedUI)
│   └── commonMain/          — domain logic, no native deps
│       App, Window, Group, ActivationHistory, SwitcherState, SwitcherEvent
├── macos/                   (new, replaces desktopApp)
│   ├── nativeInterop/cinterop/
│   │   ax.def, carbon.def, appkit_extras.def
│   ├── nativeMain/kotlin/.../native/
│   │   Ax.kt, Workspace.kt, Hotkeys.kt, EventTap.kt, IconLoader.kt,
│   │   PanelWindow.kt
│   └── nativeMain/kotlin/.../ui/
│       SwitcherPanel.kt, AppCell.kt, WindowList.kt, SettingsWindow.kt
```

### Native bindings (cinterop)

| Native API | Used for |
|---|---|
| `AXUIElement` (ApplicationServices) | enumerate per-app windows; raise without focus (`kAXRaiseAction`); commit focus (`kAXMainAttribute`) |
| `AXObserver` | per-app subscriptions: `kAXFocusedWindowChangedNotification`, `kAXWindowCreated/Destroyed`, `kAXTitleChanged` |
| `NSWorkspace` (AppKit) | running apps; `didLaunch/Terminate/ActivateApplicationNotification` |
| `NSRunningApplication` | bundle id, app icon (`icon.tiffRepresentation` → bytes → Compose `ImageBitmap`) |
| `RegisterEventHotKey` (Carbon) | global cmd+tab and cmd+\` registration. Does **not** require AX permission. |
| `CGEventTap` on `kCGEventFlagsChanged` | detect cmd release to commit; needs AX permission |
| `NSWindow.level` / `NSPanel` | promote the Compose window to floating, non-activating |
| _(Plan B for cross-space)_ `CGSCopyWindowsWithOptionsAndTags` | private SkyLight; isolated cinterop module, off by default |

### Activation history model (in `shared`)

- `ActivationHistory` keeps two LRU lists: apps, and windows-per-app.
- Mutated only by two real events:
  - `NSWorkspace.didActivateApplicationNotification` → bump app.
  - `kAXFocusedWindowChangedNotification` (per-app AX observer) → bump
    window inside that app.
- A single in-flight flag `isPreviewing` is set while the switcher is open;
  AX/Workspace events received during that window are buffered and discarded
  on commit (they describe our own raises, not user intent).

### Raising a window without polluting history

- **Preview raise** (during navigation):
  `AXUIElementPerformAction(window, kAXRaiseAction)` — raises without making
  the app key, doesn't fire `didActivateApplicationNotification`.
- **Commit** (on cmd-release):
  `[NSRunningApplication activateWithOptions:.activateIgnoringOtherApps]` +
  `AXUIElementSetAttributeValue(window, kAXMainAttribute, true)` +
  `kAXRaiseAction`. This is *intended* to update history.

### Switcher state machine

One state machine, two entry shortcuts:

- **App mode** (`cmd+tab`): default cursor = app[1].window[0] (second-most-recent
  app, its most-recent window). ←/→ or tab/shift-tab moves between apps.
- **Window mode** (`cmd+\``): default cursor = current group's window[1]
  (second-most-recent window in the current group). ↑/↓ moves inside the
  group.

In both modes all navigation keys remain active; the user can flow between
modes without re-pressing a hotkey. Mode only sets the initial cursor.

### UI

- Borderless transparent `NSPanel` at `.floatingPanelLevel`,
  `nonactivating: true`. Compose renders into it.
- Horizontal row of app cells. Each cell: 64×64 icon + truncated app name +
  vertical list of window titles. Selected cell/window visualized with
  border + tint. Window list scrolls if long.
- App icons via `IconLoader.kt` from `NSRunningApplication.icon` → bytes →
  `ImageBitmap`, cached by bundle id.

### Settings

- Compose settings window opened from a menubar `NSStatusItem`.
- v1 settings:
  - Cross-space window enumeration (off; on requires the SkyLight cinterop
    module — gated behind a feature flag).
  - Modifier key for switcher (cmd default; alt allowed).
  - Hide-minimized / hide-hidden toggles.
  - Sort: recency vs. alphabetical.
- Persisted as JSON via `kotlinx-serialization` at
  `~/Library/Application Support/KAltSwitch/config.json`.

### Permissions & entitlements

- **Accessibility permission**: required at launch. Probe with
  `AXIsProcessTrustedWithOptions`; if false, show a Compose dialog with a
  button that opens System Settings → Privacy → Accessibility.
- **Screen Recording permission**: not required (no thumbnails).
- Entitlements: `com.apple.security.app-sandbox = false`,
  `com.apple.security.cs.disable-library-validation = true` (only needed
  if/when SkyLight is enabled).

## Critical files to modify or create

- `settings.gradle.kts` — replace `desktopApp` with `macos`; rename `sharedUI`
  → `shared`.
- `shared/build.gradle.kts` — add `macosArm64()` target; commonMain only,
  no JVM-only deps.
- `shared/src/commonMain/kotlin/com/shish/kaltswitch/model/`
  — `App.kt`, `Window.kt`, `Group.kt`, `ActivationHistory.kt`,
  `SwitcherState.kt`, `SwitcherEvent.kt`.
- `shared/src/commonTest/kotlin/.../ActivationHistoryTest.kt`,
  `SwitcherStateTest.kt`.
- `macos/build.gradle.kts` — Compose application for `macosArm64`,
  cinterop sourcesets wired up.
- `macos/src/nativeInterop/cinterop/*.def`.
- `macos/src/nativeMain/kotlin/.../native/*.kt`.
- `macos/src/nativeMain/kotlin/.../ui/*.kt`.
- `macos/src/nativeMain/kotlin/Main.kt` — `NSApplication` setup, observers,
  hotkeys, Compose runner.

## Verification

- **Day-1 spike**: scaffold a Kotlin/Native `macosArm64` module that renders
  a Compose `Text("hello")` in a borderless floating NSPanel above all
  other windows. If broken, switch to Plan B before any further work.
- **Per-milestone manual checks**:
  1. AX observer fires when focus changes → `ActivationHistory` updates.
  2. cmd+tab → overlay appears with default cursor on app[1].window[0].
  3. Arrow navigation raises underlying windows; pressing Esc to cancel
     leaves activation history exactly as it was before opening.
  4. Releasing cmd activates the selected window; history updates.
  5. cmd+\` → window-mode default cursor on app[0].window[1] of focused app.
  6. cmd+H'd / minimized / multi-monitor windows appear correctly.
- **Headless tests** (`shared` commonTest): activation-history transitions,
  default-cursor logic, single-app/single-window/empty edge cases, the
  group abstraction.

## Open questions

1. **Compose-on-native-macOS maturity (Plan B trigger)**. Day-1 spike may
   reveal UI/transparency/focus issues with `compose-multiplatform 1.10.3`'s
   macOS native target. If so, prefer (a) fall back to JVM Desktop + JNA for
   *everything*, or (b) keep Compose Desktop (JVM) UI and add a Kotlin/Native
   sidecar just for AX/Carbon, IPC over a Unix socket?
2. **Selection-preview eagerness**: while a user holds an arrow key, raising
   every intermediate window is visually noisy. Prefer (a) immediate raise on
   every selection change, (b) debounce ~150ms, or (c) raise only after
   selection is stable for ~250ms?
3. **Single-app / single-window edge cases**: should the switcher still appear
   when there is only one app (cmd+tab) or only one window in the current
   group (cmd+\`), or be a silent no-op?
4. **Default window-set scope** (besides cross-space, which is settled):
   include minimized windows by default? Include `cmd+H`-hidden windows?
   Suggested defaults: minimized=yes, hidden=yes, fullscreen-on-other-space=no.
5. **Initial activation history seed**: when the app first launches, populate
   from `NSWorkspace.runningApplications` ordered by `activationPolicy` +
   each app's AX `kAXFocusedWindow`? Or start empty and only fill in as the
   user activates things?
6. **Tab vs ←/→ semantics**: are tab/shift-tab and ←/→ exactly equivalent in
   app mode, or should tab/shift-tab always move "forward/backward in
   recency" while ←/→ moves spatially in the row? Assumed equivalent.
7. **Menubar presence**: do you want a menubar `NSStatusItem` with
   "Settings…" / "Quit", or should v1 be invisible (settings opened by
   re-launching with a CLI flag)?
