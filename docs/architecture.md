# KAltSwitch — architecture

A short, current-as-of-today description of how the pieces fit together.
The why-behind-each-decision lives in
[`decisions.md`](decisions.md); this file just shows the layout so a
new contributor can find their way around.

For the long-form research that drove the decisions, see
[`window-state-attributes.md`](window-state-attributes.md).

## Two-process layout

KAltSwitch ships as one macOS `.app`. Internally it's a Swift host
shell (under `macosApp/`) that loads a Kotlin/Native static
framework (`ComposeAppMac`) built from `sharedUI/`. Splitting it that
way was a deliberate decision after the day-1 spike — see
[`decisions.md`](decisions.md) §1 for why pure Kotlin/cinterop wasn't
the right call.

```
KAltSwitch.app
├── Swift host (macosApp/macosApp/*.swift)
│     • NSApplicationDelegate, AppKit lifecycle
│     • AX observers, NSWorkspace observers
│     • Carbon hotkeys, CGEventTap, NSPanel overlay
│     • SkyLight private-API bindings
│
└── ComposeAppMac.framework  (sharedUI/, Kotlin/Native, Compose)
      • commonMain — pure-Kotlin domain logic + Compose UI
      • macosMain  — macOS glue: ConfigStore, IconLoader,
                     SystemAccent, AppRecord, StderrRedirect,
                     ComposeNSViewDelegate, native/AxPermission
```

## Module map

* **`sharedUI/`** — Kotlin Multiplatform module. The only target is
  `macosArm64` (the JVM target was dropped in iter1 — see
  [`history/PLAN-original.md`](history/PLAN-original.md) for the older
  multi-target intent that didn't materialize).
  * `commonMain/.../model/` — `App`, `Window`, `ActivationLog`, the
    switcher state machine, filters. Pure data + pure functions; the
    primary unit-test surface.
  * `commonMain/.../store/WorldStore.kt` — single mutable holder.
    `MutableStateFlow`s for everything the UI observes; mutators are
    safe from any thread.
  * `commonMain/.../switcher/SwitcherController.kt` — owns the
    per-session lifecycle (open / navigate / preview / commit /
    cancel). No Compose, no AppKit; tested end-to-end via
    `runTest(TestScope)`.
  * `commonMain/.../*.kt` — the Compose UI (App, FilteringRulesPanel,
    SwitcherOverlay, Theme, WindowTitle).
  * `macosMain/.../config/ConfigStore.kt` — JSON persistence at
    `~/Library/Application Support/KAltSwitch/config.json`.
  * `macosMain/.../log/Log.macos.kt` + `LogBridge.kt` +
    `StderrRedirect.kt` — single-line timestamp formatter, Swift-side
    bridge, stdout/stderr redirect to `~/Library/Logs/KAltSwitch.log`.
  * `macosMain/.../native/IconLoader.kt` — `NSRunningApplication.icon`
    → 128×128 PNG, pushed into `WorldStore.iconsByPid`.
  * `macosMain/.../native/SystemAccent.kt` — `NSColor.controlAccentColor`
    → `0xRRGGBB`, including the `NSSystemColorsDidChangeNotification`
    observer install.
  * `macosMain/.../native/AppRecord.kt` — read all
    `NSRunningApplication` fields into the store via one Swift→Kotlin
    call instead of a per-field round-trip.
  * `macosMain/.../native/AxPermission.kt` — trigger the macOS
    "control your computer" prompt.
  * `macosMain/.../viewcontroller/ComposeNSViewDelegate.kt` — hosts
    the Compose scene inside an `NSView`. Uses internal Compose APIs
    (suppressed) because there's no public Compose-on-macOS hosting
    yet.
  * `macosMain/.../ComposeView.kt` — singleton `WorldStore` and
    `SwitcherController`; thin StateFlow→callback bridges so the
    Swift side can observe.

* **`macosApp/`** — Swift host project, generated via
  `xcodegen` from `project.yml`.
  * `macosAppApp.swift` (the `@main`) + `AppDelegate` — install signal
    handlers, suppress system cmd+tab via `setSymbolicHotKeysEnabled`,
    mount the inspector window, mount the switcher panel, wire all
    StateFlow observers.
  * `AppRegistry.swift` — owns one `AxAppWatcher` per pid; observes
    `NSWorkspace.{didLaunch,didTerminate,didActivate,didHide,didUnhide,
    activeSpaceDidChange}`; manages AX-trust polling. The
    accent-colour and per-pid record paths now delegate into Kotlin.
  * `AxAppWatcher.swift` — per-app `AXObserver` on a dedicated
    CFRunLoop thread, marshalling state mutations to main.
  * `HotkeyController.swift` — Carbon `RegisterEventHotKey` for
    cmd+tab / cmd+\\` / cmd+shift+tab / cmd+shift+\\` / cmd+esc, plus
    a `CGEventTap` for `flagsChanged` modifier-release detection.
  * `SwitcherOverlayWindow.swift` — borderless `.nonactivatingPanel`
    that hosts the Compose overlay. Overrides `sendEvent` to catch
    `flagsChanged` / `keyUp` without AX permission.
  * `SkyLight.swift` — private-framework bindings:
    `CGSSetSymbolicHotKeyEnabled`, `_SLPSSetFrontProcessWithOptions`,
    `_AXUIElementGetWindow`, `CGSCopySpacesForWindows`,
    `CGSCopyManagedDisplaySpaces`. See `decisions.md` §2 / §4.
  * `Log.swift` — 3-line shim that forwards to the Kotlin formatter.

## Data flow

```
+--------------------------+   +---------------------------+
|  AppKit / AX / Carbon   |   |  User interaction        |
|  (Swift host observers) |   |  (Compose overlay)       |
+-----------+--------------+   +------------+--------------+
            |                                |
            v                                v
        WorldStore.recordActivation     SwitcherController.onShortcut
                |                                |
                v                                v
        WorldStore.{state, ...}         SwitcherController.ui
                |                                |
                +----------- StateFlow ----------+
                                |
                                v
                    Compose render (sharedUI)
```

* **Single source of truth.** `WorldStore.recordActivation(pid, windowId?)`
  is the *only* path that mutates the activation log AND the active-app /
  active-window pointers. Every observer (NSWorkspace, AX, the switcher
  commit path) goes through it. The contract is pinned by
  `WorldStoreTest`.
* **Switcher gate.** While `WorldStore.switcherActive == true`,
  `recordActivation` is silently dropped — the events arriving in that
  window describe our own preview-raise / commit AX echo, not user
  intent.
* **Config persistence.** `ConfigStore.{load,save}` reads/writes
  `~/Library/Application Support/KAltSwitch/config.json` synchronously.
  `ComposeView.kt`'s `configScope` debounces saves via `combine` over
  every persisted StateFlow with `drop(1)` so the initial combined
  emission doesn't immediately overwrite the file we just loaded.

## Build pipeline

`./gradlew runMacosApp` is the canonical entry point — it wraps
`xcodegen` and `xcodebuild` so we never edit the `.pbxproj` by hand.

```
./gradlew :sharedUI:embedAndSignAppleFrameworkForXcode   # called from xcode preBuildScript
   │
   v
ComposeAppMac.framework  ─┐
                          │
xcodegen generate         │
   │                      v
   v                  Xcode Build
xcodebuild ───────► KAltSwitch.app ──► open
```

The framework is rebuilt by Xcode's `preBuildScript`
(`macosApp/project.yml`) on every build, so a Swift-side `xcodebuild`
call is enough to pick up Kotlin changes.

## Useful entry points for new contributors

* **Want to add a new persisted setting?** Add a `MutableStateFlow`
  + getter + setter to `WorldStore`, add the field to `AppConfig`
  data class (`config/AppConfig.kt`), seed it in `ComposeView.kt`'s
  store-initializer, and include it in the `combine` call in
  `configScope`. UI consumes it via `store.<setting>.collectAsState()`.
* **Want a new switcher behavior?** Add an event to `SwitcherEvent`
  + state-machine case to `SwitcherState.apply`. Tests in
  `SwitcherTest` / `SwitcherControllerTest`. Hot-key wiring lives in
  `HotkeyController.swift` + `AppDelegate.applicationDidFinishLaunching`.
* **Want a new AX/Workspace observation?** Add it to
  `AxAppWatcher.handle` (per-app AX) or `AppRegistry`'s observer list
  (workspace-wide), routing the result through
  `syncActiveStateFromSystem` so the activation gating still applies.
