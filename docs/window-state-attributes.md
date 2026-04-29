# App & window state attributes — what to track and filter

Research compiled from `~/projects/alt-tab-macos` (Swift, full-featured switcher)
and `~/projects/AeroSpace` (Swift, tiling WM). The two projects converge on a
similar set of AX-driven attributes; this doc surveys them and proposes what
KAltSwitch should track and expose as filters.

---

## 1. Per-window state — the universe of attributes

| Attribute | Type | API | Used by alt-tab | Used by AeroSpace | Useful for KAltSwitch? |
|---|---|---|:---:|:---:|---|
| `cgWindowId` | CGWindowID | private `_AXUIElementGetWindow` | ✓ | ✓ | **Yes** — stable identity, lets us cross-reference with CGS |
| `title` | String | `kAXTitleAttribute` (+ `kCGSWindowTitle` fallback) | ✓ | ✓ | **Yes** — already done |
| `role` | String | `kAXRoleAttribute` | ✓ | ✓ | **Yes** — to filter non-windows |
| `subrole` | String | `kAXSubroleAttribute` | ✓ | ✓ | **Yes** — `kAXStandardWindowSubrole` vs `Dialog`, `FloatingWindow`, etc. |
| `position`, `size` | CGPoint, CGSize | `kAXPositionAttribute`, `kAXSizeAttribute` | ✓ | ✓ | Later — for thumbnails / sorting by display |
| `isMinimized` | Bool | `kAXMinimizedAttribute` | ✓ | ✓ | **Yes** — pictogram + filter |
| `isFullscreen` | Bool | `kAXFullScreenAttribute` (custom name) | ✓ | ✓ | **Yes** — pictogram + filter |
| `isFocused`, `isMain` | Bool | `kAXFocusedAttribute`, `kAXMainAttribute` | partial | ✓ | **Yes** — already used for active window detection |
| `closeButton`, `zoomButton`, `minimizeButton`, `fullScreenButton` | AXUIElement? | `kAX*ButtonAttribute` | — | ✓ | Maybe — useful only for dialog detection |
| `children` | [AXUIElement] | `kAXChildrenAttribute` | ✓ | — | Later — needed for tab detection |
| `spaceIds` | [CGSSpaceID] | `CGSCopySpacesForWindows` (private) | ✓ | partial | **Defer** — needs SkyLight; cross-space toggle |
| `screenId` | UUID | `NSScreen.screens` + `isOnScreen` | ✓ | ✓ | Later — for "windows on this monitor" filter |
| `windowLevel` | CGWindowLevel | `CGWindowListCopyWindowInfo` | ✓ | ✓ | Maybe — to filter always-on-top palettes |
| `tabbedSiblings` | [CGWindowID] | walking `AXChildren` for `AXTabGroup` role | ✓ | — | Later — Safari/Terminal tabs |
| `isHidden` (proxy) | Bool | `NSRunningApplication.isHidden` | ✓ | ✓ | **Yes** — pictogram + filter |

**Recommendation for v1**: `cgWindowId`, `title`, `subrole`, `isMinimized`,
`isFullscreen`, `isFocused`. The rest is incremental polish.

---

## 2. Per-app state

| Attribute | API | Used | KAltSwitch v1? |
|---|---|:---:|---|
| `pid` | `NSRunningApplication.processIdentifier` | both | ✓ |
| `bundleIdentifier` | same | both | ✓ |
| `localizedName` | same | both | ✓ |
| `activationPolicy` | same → `.regular` / `.accessory` / `.prohibited` | both | ✓ — already filtered |
| `isHidden` (cmd+H'd) | same | both | **Yes** — adds pictogram + filter |
| `isFinishedLaunching` | same | alt-tab (with workaround) | Maybe — to skip launching apps |
| `isTerminated` | same | both | implicit via NSWorkspace |
| `frontmost` | `NSWorkspace.frontmostApplication` | both | ✓ — already used |
| `bundleURL`, `executableURL` | same | both | Later — for icons / per-app config |

---

## 3. Filtering: what alt-tab-macos exposes

alt-tab-macos has 4 master toggles (each: show / show-at-end / hide), per
shortcut profile:

1. **`showMinimizedWindows`** — minimized windows of normal apps
2. **`showHiddenWindows`** — windows of `cmd+H`'d apps
3. **`showFullscreenWindows`** — full-screen windows on other spaces
4. **`showWindowlessApps`** — apps that exist but have zero windows (Finder is
   the canonical example; it's always running but might have no windows open)

Plus an **Exception list** keyed by bundle identifier:
- `hide: .none / .always / .whenNoOpenWindow / .windowTitleContains`
- `ignore: .none / .always / .whenFullscreen` (e.g. VirtualBox, RDC, Parallels —
  ignore their fullscreen sessions because the user wants the host's switcher,
  not the guest's)

**Default exceptions in alt-tab-macos** (`Preferences.swift`):
- `com.apple.finder` → hide when no open window (don't show Finder forever)
- `com.McAfee.McAfeeSafariHost` → always hide
- `com.vmware.fusion`, VirtualBox, Parallels, RDC, TeamViewer → ignore when
  fullscreen
- ~40 more "smart" filters in `WindowDiscriminator.swift` for individual
  oddballs (Adobe panels, Steam dropdowns, Firefox tooltips, Android Emulator
  vertical menu, Keynote fake-fullscreen, …)

The "smart" rules are the messy long tail. We do **not** need them in v1; they
can be added one-by-one as we encounter problem apps.

---

## 4. AX notifications worth subscribing to

Both projects converge on a small, stable set:

### Per-app observer
- `kAXApplicationActivatedNotification` — app gained focus
- `kAXApplicationHiddenNotification` / `kAXApplicationShownNotification` — cmd+H state
- `kAXMainWindowChangedNotification` — main window changed
- `kAXFocusedWindowChangedNotification` — focused window changed
- `kAXWindowCreatedNotification` — new window opened

### Per-window observer
- `kAXUIElementDestroyedNotification` — window closed
- `kAXTitleChangedNotification` — title changed
- `kAXWindowMiniaturizedNotification` / `kAXWindowDeminiaturizedNotification`
- `kAXWindowResizedNotification` / `kAXWindowMovedNotification` — for thumbnails / multi-display

For KAltSwitch v1, the **app-level** observer alone covers our needs (focused
window changes, window create, app hide/show). Per-window observers can be
added when we need title-change reactivity.

---

## 5. Threading model

- **alt-tab-macos**: AXObserver run loop sources are added to the main
  `CFRunLoop` via `AXObserverGetRunLoopSource`. All AX events arrive on the
  main thread. UI updates also on main.
- **AeroSpace**: spawns a *dedicated background thread per app*, each with its
  own `CFRunLoopRun()`. AX events are processed off-main, dispatched to main
  for tree updates. Considerably more complex; useful when AX queries can
  block (some apps have slow accessibility APIs).

**Recommendation for KAltSwitch**: stick with main-thread AX subscriptions
(alt-tab style). Simpler, and a switcher's AX queries are rare (one per
focus change, not per frame). If we hit a hang from a slow app, revisit.

---

## 6. Proposed KAltSwitch state (v1+)

### Window
```kotlin
data class Window(
    val id: WindowId,                  // CFHash for now; later → real CGWindowID
    val pid: Int,
    val title: String,
    val isMinimized: Boolean = false,  // kAXMinimizedAttribute
    val isFullscreen: Boolean = false, // kAXFullScreenAttribute
    val subrole: WindowSubrole = WindowSubrole.Standard,  // for filtering
)

enum class WindowSubrole { Standard, Dialog, FloatingPanel, Other }
```

### App
```kotlin
data class App(
    val pid: Int,
    val bundleId: String?,
    val name: String,
    val activationPolicy: ActivationPolicy,
    val isHidden: Boolean = false,     // cmd+H state
)
```

### Filtering settings (settings JSON, default in parens)
- `showMinimizedWindows` (true)
- `showFullscreenOnOtherSpacesWindows` (false; needs SkyLight, defer)
- `showHiddenAppWindows` (true)
- `showWindowlessApps` (true, in windowless section)
- `excludeBundleIds: List<String>` (empty by default)

### Per-row pictograms in UI
| Pictogram | Meaning |
|---|---|
| `▶` | active window of focused app |
| `─` | regular window |
| `⎽` | minimized |
| `⤢` | fullscreen (on this/another space) |
| `◌` | app currently hidden via cmd+H |
| `…` | app launching (isFinishedLaunching == false) |

Active app row already prefixed with `▶` (current behavior). The "Active:" line
above the list can go — pictogram is enough.

---

## 7. What I propose to do next, in this order

1. **AX-grant polling**: every 1s while banner is up, re-check `AXIsProcessTrusted`.
   When it flips to true, re-seed all windows and remove the banner. (Trivial.)
2. **Drop the "Active:" header line**; keep the `▶` row prefix.
3. **Window state plumbing**: extend `Window` with `isMinimized` /
   `isFullscreen` from AX, render as pictograms.
4. **App `isHidden` plumbing**: from `NSRunningApplication.isHidden`, pictogram + filter.
5. **Settings model**: add the four toggles from §6 to a JSON file at
   `~/Library/Application Support/KAltSwitch/config.json`. UI for them comes
   later — start by reading the config on launch.
6. **Per-app AXObserver**: replaces the 500ms polling. Now that we know what
   to subscribe to, this becomes mechanical.

Filtering "irrelevant" windowless apps is mostly handled by the per-app
`activationPolicy != .regular` filter in alt-tab — but we relaxed that so
accessory apps with windows show up. The two-section UX (with-windows vs
windowless) makes the windowless apps visually less intrusive, and we can add
`excludeBundleIds` for the genuinely-never-want apps.

---

## 8. Deferred / known limits (post-MVP)

- **AeroSpace-style "agent app" focus path (alternative to SkyLight)**: as of
  this write-up the switcher uses SkyLight private APIs
  (`_SLPSSetFrontProcessWithOptions` + `SLPSPostEventRecordTo` +
  `_AXUIElementGetWindow`) to switch frontmost without our process being
  active. The reason: macOS 14+ silently no-ops
  `NSRunningApplication.activate(options:)` when the caller isn't the active
  app, and our overlay panel is `.nonactivatingPanel` so we never become
  active.

  AeroSpace and alt-tab-macos avoid the SkyLight dependency by **shipping as
  agent apps** (`LSUIElement = YES` in Info.plist) and *making their overlay
  active*. Once the calling process is active, plain `nsApp.activate(options:)`
  works without private APIs. Concrete delta if we wanted to move there:

  - Set `INFOPLIST_KEY_LSUIElement: YES` in `macosApp/project.yml`. This
    drops our Dock icon, removes us from the system cmd+Tab, and gives the
    user no way to launch the inspector window other than via NSStatusItem,
    `applicationShouldHandleReopen`, or a global shortcut.
  - Drop `.nonactivatingPanel` from `SwitcherOverlayWindow.styleMask` so
    `makeKey()` activates our process.
  - Gate `syncActiveStateFromSystem` on `switcherActive == false` (already
    done for `recordEvent`); otherwise the inspector will show KAltSwitch as
    the active app for the duration of the overlay.
  - Possibly install an empty `NSApp.mainMenu = NSMenu()` to suppress the
    default app menu that would otherwise appear in the system menu bar
    while the overlay is up.

  Trade-offs vs. the SkyLight path:
  - Pro: no private APIs.
  - Con: visual flicker — original frontmost app loses focus to us at
    overlay-open, gets it back (or the new target gets it) at commit. With
    a fast `showDelay` it's barely perceptible, but it's not zero.
  - Con: original app receives `applicationDidResignActive`. Some
    apps (video players, games) react to that (pause, throttle render).
  - Con: while overlay is open, original app stops receiving keyboard
    input entirely (we steal it). With nonactivating panel, all non-modifier
    keystrokes still flow to the original app.
  - Con: needs NSStatusItem + reopen-handler before this can ship — without
    them the inspector becomes unreachable after first close.

  Bottom line: AeroSpace path is "ship as agent + accept brief activation
  flicker"; SkyLight path is "stay in background + use private APIs to
  bypass activation gating". We chose SkyLight for MVP because it preserves
  the calmer UX during selection. If/when SkyLight stops working on a future
  macOS, this is the documented backup plan.

- **Preview-raise on selection (high-priority post-MVP)**: the controller has
  the `previewDelay` timer and an `onRaiseWindow` callback wired all the way
  through to `AxAppWatcher.raiseWindow` (`kAXRaiseAction`), but
  `previewEnabled = false` in `SwitcherController` keeps the path dormant for
  MVP. Reasons for deferring:
  - The `kAXRaiseAction` raise visibly reorders other windows behind our
    overlay, which conflicts with the spec intent of "preview behind the
    panel" — we'd need a window-level z-order trick (e.g. ensure the panel
    stays above the raised window without touching app activation) before
    the UX feels right.
  - The `switcherActive` flag drops the *next* AX/Workspace activation
    event, but ChatGPT-style apps that delay their AX echo by hundreds of
    milliseconds can leak a stray `recordEvent` after `setSwitcherActive
    (false)` runs — needs a slightly longer debounce that we want to tune
    against real usage rather than guess.
  - Real users may prefer the simpler "no preview, just commit" model;
    let's gather feedback on the no-preview MVP before committing to UX
    polish here.

  Re-enable: pass `previewEnabled = true` to the `SwitcherController`
  constructor in `sharedUI/.../macosMain/.../ComposeView.kt`. Two unit tests
  in `SwitcherControllerTest` already opt-in via the same flag, so the timer
  / cursor-reset / visibility behaviour stays covered.

- **Parent-child window relinking**: implemented for two cases — (a) typed
  attributes `AXSheets`/`AXDrawers`/`AXChildWindows`, (b) top-level windows
  whose `kAXParent` points at another top-level window in the same app.
  But many apps (Firefox save dialog confirmed via Accessibility Inspector,
  IntelliJ popups, Mail composer) report their dialog/popup `kAXParent` as
  the application element rather than the parent window. Without private
  APIs like `_AXUIElementGetWindow` + CGS to cross-reference, we can't
  distinguish "genuine new top-level window" from "logically a child whose
  AX tells us nothing". Defer until after MVP.

- **AX-resistant Electron apps (ChatGPT)**: when the user has to grant
  Accessibility permission *during* a session (rather than having granted
  it before), ChatGPT's `kAXWindows` returns `nil` indefinitely. The path
  where AX was granted in a previous session and persists across launches
  works fine for ChatGPT.

  Reproducible symptom (logs from a fresh post-grant run):
  ```
  AX trusted = false                              ← startup
  kAXWindows nil for pid=N                        ← initial refresh, AX denied
  AX trust changed to true                        ← user just granted
  kAXWindows nil for pid=N                        ← respawned watcher, still nil
  (no further changes — AX never recovers)
  ```

  Things tried that did **not** fix it:
  1. Re-spawning the per-app watcher after AX trust flips (so a fresh
     `AXObserverCreate` runs with full permission).
  2. Subscription retry: tracking which `AXObserverAddNotification` calls
     succeeded vs failed (with `kAXErrorAPIDisabled`/`-25211`) and retrying
     missing ones on every `refreshAllWindows`.
  3. Triggering refresh on `NSWorkspace.didActivate` for the app
     (`watcher.requestRefresh()`).
  4. A 5-second bulk-refresh timer in `AppRegistry` that asks every watcher
     to refresh, in case AX cooperation arrives after some delay.
  5. Setting the `AXEnhancedUserInterface` and `AXManualAccessibility`
     attributes on the app element to `true`. This is the documented signal
     screen readers use to opt Chromium-based apps into AX. ChatGPT
     ignored it.

  Hypothesis: ChatGPT (Electron-based, with custom hardening) refuses
  AX cross-process queries from any client that wasn't trusted *at app
  launch time*. Other AX-friendly apps tolerate mid-session permission
  grants. Likely fixes for post-MVP:
  - Use private `_AXUIElementCreateWithRemoteToken` / CGSWindowList
    fallback to enumerate ChatGPT's windows via Core Graphics rather than
    AX (alt-tab-macos and AeroSpace both rely on CGS for stubborn apps).
  - Or detect AX-permanently-disabled apps and tell the user to relaunch
    them after granting permission — restart of the *target* app fixes
    it.

  Workaround for users today: relaunch ChatGPT after granting AX
  permission (or grant permission *before* the first switcher run).

## 9. Open questions for you

- **Subrole filtering**: alt-tab has ~40 app-specific exception rules that
  filter out tooltips, dropdowns, panels. v1 plan: only show
  `kAXStandardWindowSubrole`. Acceptable, or do you want all subroles shown?
- **`showWindowlessApps`** default: currently we always show them (in the
  windowless section). alt-tab has a "show at end" / "hide" toggle. OK as-is?
- **Per-window vs per-app AXObserver**: I propose per-app only for v1 (app
  observer fires events on focused-window-changed and window-created, which
  covers most needs). Per-window observer needed only for live title-change
  reactivity. Defer per-window?
