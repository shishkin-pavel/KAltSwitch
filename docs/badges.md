# App badges

Pill-shaped indicators rendered top-right of an app's icon in the switcher
overlay. Two distinct sources, only one of which is wired up today.

## Stage 1 — Dock-tile badges (shipped)

Source: the macOS Dock's accessibility tree. The Dock is itself an
accessible app (`com.apple.dock`); each running / pinned app is an
`AXApplicationDockItem`-subroled child whose `AXURL` points at the .app
bundle and whose `AXStatusLabel` carries the badge string the app set
via `NSDockTile.badgeLabel`.

Components:

* **`DockBadgeWatcher`** (Swift) — owns the Dock's `AXUIElement`,
  iterates the children twice on start (top level + one nested level so
  we don't depend on the exact `AXList` wrapping), reads `AXURL` +
  `AXStatusLabel` per item, registers a `kAXValueChangedNotification`
  observer per item for live updates, and pushes results into the
  store.
* **`AppRegistry`** spins it up alongside the per-pid `AxAppWatcher`s,
  rescans on `NSWorkspace.didLaunchApplicationNotification` (with a
  300 ms delay so the Dock has time to publish the new tile), and
  rebuilds it on AX-trust flip alongside `respawnAllWatchers()`.
* **`WorldStore.setAppBadge(pid, text)`** — single-field mutator so the
  AppRegistry → `upsertApp` path doesn't clobber the badge on every
  workspace event. `upsertApp` also explicitly preserves the previous
  record's `badgeText` when the caller didn't set one.
* **`SwitcherOverlay.DockBadge`** — red pill (systemRed `0xFFFF3B30`),
  white bold text, `widthIn(min=18.dp).height(16.dp)`. Sits on top of
  the icon's TopEnd via the same `offset()` trick as the existing
  hidden-status badge. When both the dock badge and the hidden
  pictogram are present, the hidden one drops below (`y = +14.dp`)
  instead of overlapping.

Matching: dock items resolve to a pid via
`NSWorkspace.shared.runningApplications.first(where:
$0.bundleURL?.standardizedFileURL.path == dockUrl.standardizedFileURL.path)`.
First match wins for multi-instance apps — fine for the badge-bearing
ones (Mail / Slack / Telegram / iMessage are single-process).

Failure modes:

* No AX permission → `AXObserverCreate` returns an error, we log + skip;
  badges silently stay nil. Re-attaches once trust flips on (via
  `respawnAllWatchers`).
* Dock not running (extremely rare — happens during Dock crash/restart)
  → start() bails out; user has to relaunch the switcher to retry.
* Apps launched via Spotlight that haven't materialised in the Dock
  yet → covered by the 300 ms re-scan; if they take longer, the next
  workspace launch event also triggers a rescan.

Memory: AX retains the dock-item element while a notification is
attached. We never call `AXObserverRemoveNotification` per item (same
pattern as `AxAppWatcher`); items leak until the watcher itself dies
on app termination. Bounded by the dock's item count — not a concern.

## Stage 2 — Custom badge rules (TODO)

Goal: a user-configurable settings section that lets the user attach
badges to apps based on signals other than the Dock tile. Concrete
prompts:

* **Firefox / Chrome profiles.** Multi-profile browsers run as one
  process per profile; the Dock shows one badge for *all* of them
  combined (or none at all). Profile names live in window titles
  (`Mozilla Firefox – Work`) and command-line args (`-P Work` /
  `--profile-directory=Work`). A rule like *"if argv contains `-P` then
  badge = the value after it"* would let the user surface the profile
  on the switcher.
* **VS Code / Cursor / similar.** Window titles encode the workspace
  (`MyProject — Visual Studio Code`); a rule could badge with the
  project name short form.
* **Terminal panes.** No dock badge but informative window titles —
  same shape of rule.

Shape of the settings UI (rough):

* Per-rule: matcher (app bundle id / name) + signal (window title regex
  / argv regex / executable path regex / static text) + capture group
  → badge string. Stored in `AppConfig` under a new field; persisted
  through the existing `ConfigStore` save path.
* Evaluation: a new `BadgeRules` model in `commonMain` mapping
  `(App, List<Window>)` → `String?`. Custom-rule output overrides the
  Dock value when both produce something (custom rules are explicit
  user intent). Live-recomputed via `combine(state, rules)` on the
  store.
* For argv we need `proc_pidpath` + `proc_pidargs` (sysctl) on the
  Swift side, exposed through a small bridge into `App` (a new
  optional `argv: List<String>?` field gated behind "any rule actually
  needs argv").

Open questions to resolve before building:

* Should custom rules also override "no badge" with "•" so e.g. a
  badgeless Firefox profile still shows *some* indicator?
* Avatar / colour overrides for profiles vs plain-string badges? A
  Firefox profile is more recognisable as a colour swatch than as
  the literal text `Work`.
* Multi-window cells: should the badge be per-window (rule applied
  to each window's title) or per-app (first match wins)? Probably
  per-window for the title-driven rules.
