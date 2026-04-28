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

## 8. Open questions for you

- **Subrole filtering**: alt-tab has ~40 app-specific exception rules that
  filter out tooltips, dropdowns, panels. v1 plan: only show
  `kAXStandardWindowSubrole`. Acceptable, or do you want all subroles shown?
- **`showWindowlessApps`** default: currently we always show them (in the
  windowless section). alt-tab has a "show at end" / "hide" toggle. OK as-is?
- **Per-window vs per-app AXObserver**: I propose per-app only for v1 (app
  observer fires events on focused-window-changed and window-created, which
  covers most needs). Per-window observer needed only for live title-change
  reactivity. Defer per-window?
