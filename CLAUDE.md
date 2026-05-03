# Project conventions

## Git workflow during feature work

While iterating on a feature branch:

* **Do not `git commit --amend`** between rounds of feedback. Each change should land as its own commit so the diff between iterations is reviewable.
* Use **fixup commits** with a short, specific subject — `git commit -m "fixup: <one-line what changed>"` (or `git commit --fixup=<sha>`).
* Build + test after each fixup, same as a normal commit.

When the feature is verified complete:

* **Squash all the iter / fixup commits into a single commit** with a comprehensive message describing the architectural change.
* **Rebase the squashed commit onto `main`** — do not use `git merge --squash`. The history on `main` should look like a clean fast-forward of one focused commit.

Why: the iter / fixup commits exist for the *reviewer's* sake during the feature; they are not part of the project's permanent history. The user reads them between rounds to verify each step landed as expected.

## Separating unrelated changes

If during work on a feature branch we make commits that aren't part of the feature itself — project conventions, unrelated docs, incidental refactors learnt along the way — those changes must be **split out into their own commits on `main`**, separate from the feature's squashed commit.

Why: the squashed feature commit on `main` is the unit a reviewer reads to understand "what did this feature actually change?". Mixing in unrelated drift makes both halves harder to review and harder to revert independently.

Practically: when collecting commits for the squash, audit the cumulative diff before `git reset --soft main` and pull anything off-theme into its own commit (chronologically before or after the feature commit, whichever reads more naturally).
