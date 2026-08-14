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

### P3-11: Derive Feature Scope ✅ Keep 2026-08-14

Keep the derive feature rather than cutting it or gating it behind a setting.
Derive is one unobtrusive entry point (a button plus input dialog in the preset
editor) that atomically creates a feature branch across the main repository and
all submodules — a multi-repository operation stock git cannot do in one step.
It is covered by focused tests (executor, runner, notification). It is a
self-contained parallel implementation rather than shared switch logic, so any
future reduction is a convergence toward switch's OperationIssue/notification
machinery, not a deletion.

### P3-12: Recovery/Rollback Scope ✅ Keep 2026-08-14

Keep the full rollback surface rather than surfacing only the stash.
Auto-rollback to the checkpoint HEAD plus stash restore is the tool's core
safety property: a failed or interrupted switch otherwise strands the user on a
new branch with a half-updated submodule tree. Each guard (identity recheck,
clean-worktree requirement, at-most-once stash apply, retained-submodule
tracking) maps to a concrete documented failure mode rather than speculative
complexity. Reducing to "surface the stash" would remove the self-healing
behavior recovery exists to provide.

### P3-13: Responsive Layout — Consolidate Duplicated Helpers

Keep the hand-rolled responsive layout rather than replacing it with stock
managers. The adaptive stacking, the 340 px compact transition, and the grouped
overflow menu are the product's visual identity and cannot be reproduced by
stock Swing/IntelliJ layout; `UiLayoutTest` already pins each component's
behavior. The remaining work is a pure-extraction tidy-up with no visual change:

- Share one `COMPACT_WIDTH = JBUI.scale(340)` constant between `GlobalActionBar`
  and `CollapsibleActionBar` so the two compact thresholds cannot drift.
- Extract the repeated `centeredY` / available-content-width / content-left
  calculations into shared helpers next to `effectiveLayoutWidth()`.
- Unify the metric property-listener registration (5 / 3 / 0 / 0 properties
  across the row panels) into one helper, closing the gap where `GlobalActionBar`
  and `TrailingControlRowPanel` do not relayout when a button's text or icon
  changes.

### P3-14: Collapse The Git Interface Hierarchy

`GitClient.kt` defines 14 role interfaces backed by one production implementation
(`GitCommandClient`). Collapse to the concrete client plus a minimal test-double
boundary, and drop the never-hit `as?` batch fallbacks.
