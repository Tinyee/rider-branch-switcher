# Roadmap

This file contains only future project work and explicit non-goals. Released
changes belong in [`../CHANGELOG.md`](../CHANGELOG.md), current product
capabilities in the [README](../README.md), and completed technical decisions
in [`review-history.md`](review-history.md).

## P1: Prepare The First Public Release

- Run `./gradlew releaseCheck` against the final commit.
- Perform a Rider smoke test:
  - load and save presets
  - switch between two complete presets
  - cancel an active Git command and verify recovery
  - initialize a missing submodule
  - derive and undo a feature branch
  - verify dark/light themes and Chinese/English text
- Confirm the three README screenshots match the final plugin build.
- Review Marketplace description, license, vendor details, icon, compatibility
  wording, and ZIP contents.
- Publish only after Plugin Verifier and the Rider smoke test pass.

### Pre-release UX: Shortcut Entry Consistency ✅ Done 2026-08-13

Two user-facing gaps made the "Switch to Preset" shortcut (Ctrl+Alt+B, i.e.
Ctrl+Option+B on macOS) feel disjointed from the sidebar and fail to scale with
the number of presets. Both were closed: the shortcut now picks from a
filterable chooser (type-to-filter, per-row branch summary) and confirms through
the same `SwitchPreviewDialog` as the sidebar. Recorded in the
[changelog](../CHANGELOG.md).

## P2: Expand Compatibility Evidence

The unified IntelliJ IDEA distribution is the primary build target and Rider
is the current compatibility target. Follow the evidence requirements in the
[support matrix](SETUP.md#support-matrix-policy) before advertising another IDE
family.

Candidate verification order:

1. PyCharm Professional
2. WebStorm
3. CLion

## P3: Targeted Maintainability

This is the complete active P3 inventory. Completed state-refresh, recovery
plan, and structured-diagnostic work is recorded in
[`review-history.md`](review-history.md).

### P3-08: Re-evaluate The Git Backend With Evidence

The plugin uses the Git CLI. Consider a Git4Idea migration only after measured
evidence identifies a compatibility, performance, or credential-handling
limitation that the CLI implementation cannot reasonably address.

This item does not currently block maintenance or the first release.

### P3-09: Automate Marketplace Publishing After The First Release

Publish 0.8.0 manually after the release checklist passes. Once that process is
proven, add a release-only workflow that validates the tagged source version,
runs `releaseCheck`, publishes with the protected Marketplace token, and keeps
ordinary pushes unable to publish.

This item starts only after the first successful manual Marketplace release.

### P3-10: Graceful Git Process Termination ✅ Done 2026-08-11

`GitProcessRunner.terminateProcess` now sends SIGTERM to the process tree first
and waits briefly for a cooperative exit (so git can remove its own `index.lock`),
then falls back to SIGKILL only for processes still alive after the grace window.
The descendant shutdown paths were validated and are covered by tests. Recorded
with the surrounding index-lock hardening in `review-history.md`.

### P3-11: Decide The Derive Feature Scope

The derive (feature-branch creation) path spans ~930 LOC and is a separate action
from switching. Decide whether to keep it, cut it, or gate it behind a setting.

### P3-12: Decide The Recovery/Rollback Scope

Recovery, checkpoint, and rollback span ~650 LOC to support undoing a failed
switch. Keep the checkpoint-before-mutation and stash-restore safety nets, but
evaluate whether the full rollback surface is worth its weight versus simply
surfacing the stash.

### P3-13: Replace The Custom Responsive Layout

~1024 LOC of hand-rolled null-layout (`ResponsiveRowPanel`, `ViewportWidthPanel`,
`PopupAction`, `CollapsibleActionBar`, `FeedbackIconButton`, `GlobalActionBar`)
replicate standard Swing/IntelliJ layout behavior. Evaluate replacing them with
stock layout managers plus a minimum tool-window width.

### P3-14: Collapse The Git Interface Hierarchy

`GitClient.kt` defines 14 role interfaces backed by one production implementation
(`GitCommandClient`). Collapse to the concrete client plus a minimal test-double
boundary, and drop the never-hit `as?` batch fallbacks.
