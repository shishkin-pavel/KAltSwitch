# Project conventions

## Git workflow during feature work

While iterating on a feature branch:

* **Do not `git commit --amend`** between rounds of feedback. Each change should land as its own commit so the diff between iterations is reviewable.
* Use **fixup commits** with a short, specific subject — `git commit -m "fixup: <one-line what changed>"` (or `git commit --fixup=<sha>`).
* Build + test after each fixup, same as a normal commit.

### Don't re-run build/tests when there are no source changes

The build/test gate is for catching regressions from code changes. Git operations that leave the working tree byte-identical to a state that already passed — `git reset --soft`, switching to a tree-equal commit, applying an already-applied stash, ff-merging a verified branch — don't change what the compiler sees. Skip the verify step in those cases; the previous green run still holds. Re-running anyway burns time and adds nothing.

The trigger for the gate is "did source files change since the last green run?", not "is a commit about to happen?".

When the feature is verified complete:

* **Squash all the iter / fixup commits into a single commit** with a comprehensive message describing the architectural change.
* **Rebase the squashed commit onto `main`** — do not use `git merge --squash`. The history on `main` should look like a clean fast-forward of one focused commit.

Why: the iter / fixup commits exist for the *reviewer's* sake during the feature; they are not part of the project's permanent history. The user reads them between rounds to verify each step landed as expected.

## Separating unrelated changes

If during work on a feature branch we make commits that aren't part of the feature itself — project conventions, unrelated docs, incidental refactors learnt along the way — those changes must be **split out into their own commits on `main`**, separate from the feature's squashed commit.

Why: the squashed feature commit on `main` is the unit a reviewer reads to understand "what did this feature actually change?". Mixing in unrelated drift makes both halves harder to review and harder to revert independently.

Practically: when collecting commits for the squash, audit the cumulative diff before `git reset --soft main` and pull anything off-theme into its own commit (chronologically before or after the feature commit, whichever reads more naturally).
