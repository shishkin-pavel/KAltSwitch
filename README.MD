# KAltSwitch

A cmd+tab replacement for macOS, written in Kotlin/Native + Compose
Multiplatform with a thin Swift/AppKit host shell.

## Running

```
./gradlew runMacosApp
```

This runs `xcodegen` + `xcodebuild` + `open` in one shot. After the
first launch macOS will prompt for Accessibility permission — grant it
under **Settings → Privacy & Security → Accessibility → KAltSwitch**;
the symbolic-hotkey takeover and `CGEventTap` paths need it.

After a build, the `.app` lives at
`macosApp/build/derived-data/Build/Products/Debug/KAltSwitch.app` and
can be launched directly with `open`.

## Tests

```
./gradlew check         # all Kotlin unit tests (commonTest + macosArm64Test)
./gradlew buildMacosApp # full Xcode build of the .app
```

## Logs

Stdout + stderr go to `~/Library/Logs/KAltSwitch.log`. Each launch
starts with a `=== SESSION START ...` marker, so you can slice the log
to the most recent session in one shell command:

```
awk '/^=== SESSION START/{buf=""}{buf=buf$0"\n"}END{print buf}' \
  ~/Library/Logs/KAltSwitch.log
```

## Architecture & decisions

* [`docs/architecture.md`](docs/architecture.md) — current code layout
  and module map. Start here.
* [`docs/decisions.md`](docs/decisions.md) — decisions log, one entry
  per non-obvious-from-the-code choice.
* [`docs/window-state-attributes.md`](docs/window-state-attributes.md)
  — long-form research that fed the decisions.
* [`docs/filters-redesign.md`](docs/filters-redesign.md) — filtering
  rules redesign notes.
* [`docs/history/`](docs/history/) — superseded planning documents.

## Project shape

* `sharedUI/` — Kotlin Multiplatform module (`macosArm64` only). Pure
  domain logic in `commonMain`, macOS glue + Compose-on-NSView host in
  `macosMain`. Exports `ComposeAppMac.framework`.
* `macosApp/` — Swift host. `xcodegen`-generated, never hand-edited.
  Owns AppKit lifecycle, AX observation, Carbon hotkeys, NSPanel
  overlay, SkyLight private-API bindings.
