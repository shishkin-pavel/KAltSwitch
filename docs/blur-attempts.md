# Switcher panel blur — history of attempts

The switcher overlay used to have an `NSVisualEffectView` behind the
Compose content for a system-style blur backdrop (vibrant rounded
rectangle, like Spotlight or the cmd+tab bar). It was removed in the
`switcher-click-through-fix` branch because the architectural change
to dynamic panel sizing introduced rendering artefacts we couldn't
solve cleanly. This file records what we tried and what failed, so
the next attempt can avoid the same dead ends.

## Architectural context

Before the click-through fix, the panel was a fixed ~90 % of the
active screen and the blur sat in the centre at the visible content
size, surrounded by a transparent margin. After the fix the panel
itself shrinks to the content size on every Compose layout pass
(`onPanelSize` → `setContentSize`), so the blur — which originally
filled the visible content rect via autoresize — now needs to track
*two* moving targets: the panel's outer frame **and** the visible
backdrop's rounded shape (Compose's `animateContentSize` runs a
200 ms tween on the visible Box).

The challenge: keep the blur visually in sync with Compose's tween,
without flickers, while also keeping a 16 dp rounded corner.

## Approach 1 — `NSVisualEffectView` with static `maskImage` (pre-iter40)

Worked when the panel was a fixed large rect and the blur was a
sub-rect with a 9-slice mask image. Stopped working as soon as we
started resizing the blur per layout: the static mask scales, but
the rounded corners no longer match the Compose backdrop's corners
during animation, so the visible blur edge "breathes" out of sync
with the rounded plate Compose draws on top.

Verdict: rejected by the new sizing model.

## Approach 2 — `CAShapeLayer` set as `blur.layer.mask`, animated path

Commit: `eda2dbc cleanup J: animate blur via CAShapeLayer mask synced with Compose tween`.

Replaced the `maskImage` with a `CAShapeLayer` whose `path` is
animated via `CABasicAnimation` (200 ms, control points
`(0.4, 0.0, 0.2, 1.0)` matching Compose's `FastOutSlowInEasing`).
The mask path animation runs in parallel with Compose's
`animateContentSize`, so in theory the visible blur shrinks/grows
in lockstep with the rounded backdrop above it.

**Failure:** intermittent **transparent flicker** of the panel for
a single frame during the path animation.

Root cause (partially diagnosed): `CALayer` fires a default 0.25 s
implicit action when `path` is set. That implicit action composed
with our explicit `CABasicAnimation` to produce an interpolated
path that *under-covered* the visible region for a frame. We tried
suppressing implicit actions:

> Commit: `0858f7f fixup: suppress CALayer implicit path action to stop blur flicker`
>
> Wrap the model-value updates in `CATransaction.setDisableActions(true)`
> so only the explicit animation drives the visible interpolation.
> Also add layer-level autoresize on the mask.

The flicker still appeared. We suspect `NSVisualEffectView`'s
internal rendering doesn't reliably honour custom `layer.mask`
state during certain transitions — it's an Apple-private view that
re-installs its own backing layers on resize/blur-state changes,
and our mask gets out of phase with whatever it's doing internally.

Verdict: animation timing was correct in principle but the
interaction with `NSVisualEffectView`'s opaque internals was
unreliable.

## Approach 3 — `NSVisualEffectView` inside an `NSView` container with `cornerRadius` + `masksToBounds`

Commit: `c3b82cd fixup: replace CAShapeLayer mask with rounded NSView container`.

Side-stepped the `layer.mask` problem entirely. Wrapped the
`NSVisualEffectView` in a plain `NSView` (`blurContainer`) with
`cornerRadius = 16` + `masksToBounds`. The blur autoresizes to
fill the container; the container's `frame` is what we animate
via `NSAnimationContext.animator()` (Apple-blessed path, no
implicit-action conflicts, same 200 ms / cubic-bezier as Compose).

This is the standard AppKit pattern and produced a clean
animation in the happy path. But it had a worse failure mode:

**Failure:** after a few mid-session resizes the blur disappeared
entirely and stayed gone until the app was restarted.

Root cause: not investigated. Plausible suspects:
- Compositing ordering: `NSVisualEffectView` requires its layer
  hierarchy to be set up a specific way to read pixels behind it;
  re-parenting under a layer-backed container with `masksToBounds`
  may trip a "blur source = nothing" path internally.
- Layer-backing toggling: setting `cornerRadius` forces the
  container layer-backed; combined with the panel's transparent
  background and `level = .popUpMenu`, the blur source surface may
  not be re-acquired across some transitions.

Verdict: animation was clean but the blur is fragile and silently
breaks; not shippable.

## Decision — remove blur entirely (current state)

Commits:
- `f8676ab fixup: disable blur entirely until a working sync approach is found`
- followed by the cleanup that adds this doc and removes all
  remaining blur references from comments.

The Compose visible backdrop is now a fully-opaque rounded plate
(`Color(0xFF1B1B1F)` + 1 dp border). It loses the system-style
"frosted glass" feel but is reliable and animates correctly via
Compose's `animateContentSize` alone — no AppKit cooperation
required.

## Ideas for a future attempt

1. **Compose-level blur on the snapshot of what's behind**: capture
   the screen-behind-panel into a Skia bitmap once at session
   start, blur it in Compose (`Modifier.blur`), and crossfade
   while the panel resizes. No `NSVisualEffectView` involved.
   Drawback: snapshot is stale — content under the panel keeps
   moving and the blur won't reflect it. Acceptable for a
   transient overlay (the switcher is open <1 s typically).

2. **Two-step animation**: don't try to animate the blur shape at
   all. On every size change, snap blur to the *envelope* of
   old+new size during the 200 ms tween, then snap to the final
   size after. Compose's rounded plate stays animated; blur is
   a static larger rect underneath. Some blur shows outside the
   plate during the tween — acceptable if the plate's corners
   mask it visually.

3. **Skia `BackdropFilter` equivalent**: Compose Multiplatform
   doesn't have a built-in BackdropFilter, but Skiko exposes
   `SkImageFilter::Blur`. With access to the underlying Skia
   surface we could draw a blur of the captured behind-panel
   region as a Compose layer. More research needed on whether
   the Compose-MP scene gives us that hook.

4. **Custom `NSView` that draws blur via `CIFilter.gaussianBlur`
   over a snapshot of `windowList(below:)`**: skip
   `NSVisualEffectView` entirely. More control, more code to
   maintain.

If any of these is attempted, run the manual reproducer that
broke approach 3: open the switcher, then type cmd+tab quickly
five or six times in succession with different windows under the
mouse — blur disappearing after a few resizes was the failure
mode that ended the last attempt.
