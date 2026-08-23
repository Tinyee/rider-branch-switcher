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

### P3-08: Re-evaluate The Git Backend With Evidence ✅ Done 2026-08-15

Evaluated against measured evidence and the CLI implementation is retained.
Process budgets are already near-minimal (one porcelain-v2 status process per
repository, at most three first-time preflight processes, four-process global
cap), CI passes on three OSes, and the hardened process lifecycle (graceful
tree termination, output caps, structured failures, per-operation
cancellation) has no known compatibility, performance, or credential gap.
Git4Idea would not remove process spawning for writes and would replace the
pinned process-layer tests and index-lock-safe termination with an internal
API that still lacks the distinctive commands (porcelain-v2 batch status,
`git config --null` .gitmodules reading, stash-by-oid apply).

Re-evaluate only when one of these appears: a user reports a submodule fetch
failing because credentials live only in the IDE credential store, a
compatibility report where `git` is unavailable on PATH but configured in the
IDE (or the reverse), or Git4Idea API usage in the plugin grows beyond the
single VCS-refresh call.

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

### P3-13: Responsive Layout — Consolidate Duplicated Helpers ✅ Done 2026-08-15

Kept the hand-rolled responsive layout (the adaptive stacking, 340 px compact
transition, and grouped overflow menu are the product's visual identity and
cannot be reproduced by stock Swing/IntelliJ layout). The tidy-up is complete
with no visual change:

- Shared one `COMPACT_WIDTH = JBUI.scale(340)` constant between `GlobalActionBar`
  and `CollapsibleActionBar` so the two compact thresholds cannot drift.
- Extracted the repeated `centeredY`, `availableContentWidth`, and `contentLeft`
  calculations into shared `JComponent` helpers next to `effectiveLayoutWidth()`.
- Unified the metric property-listener registration into one
  `registerMetricsRelayout` helper, closing the gap where `GlobalActionBar` and
  `TrailingControlRowPanel` did not relayout when a button's text or icon
  changed.

`UiLayoutTest` (10 tests), the full suites (189 + 166), Detekt, and `quickCheck`
all pass.

### P3-14: Git Interface Hierarchy ✅ Keep 2026-08-15

Keep the capability-oriented hierarchy rather than collapsing to a concrete
client. Both collapse premises fail on evidence: the `as?` batch fallbacks are
not dead code — narrow test doubles (10 sites; e.g. an `IndexLockBlockTest`
fake implementing only `indexLockFile`) rely on them — and `core` cannot depend
on the concrete `GitCommandClient` (it lives in the plugin module and imports
an IntelliJ Logger), so the interfaces are the dependency-direction carrier and
the actual minimal test-double boundary. Every remaining interface has its own
production consumer (switch steps, derive, preflight, preset discovery,
repository state) and narrow fakes.

The cleanup is done: `GitCancellation` (zero direct references, only inherited
by `SwitchGitClient`) is merged into `SwitchGitClient`, reducing the count from
14 to 13 with no behavior change; tests, Detekt, and `quickCheck` pass. The
`WriteGuardGitClient` wrapper this section flagged is gone — the
complexity-contraction refactor deleted it and moved the index.lock
check-then-act gate into `GitCommandClient.runIndexMutation`, and
`DirtyHandlingStep` no longer inspects any wrapper.

### P3-15: Deferred Maintainability Backlog

Quality and style findings deliberately deferred from the 2026-08-21 fix
rounds, recorded in `review-history.md` so the decisions survive. These remain
open and are good first-pass maintenance work; each is small and
behavior-neutral:

- Bound the `remoteName` / `checkedProjects` caches (`GitCommandClient`,
  `GitOps`) with eviction, or document the practical cap.
- Split the nine types out of `core/model/PresetConfig.kt` into one-type-per-file.
- Replace `AppLoggerTest`'s ~20-method anonymous git fake with a shared fake.
- Remove the dead `changed` computation in
  `PresetLoader.normalizePresetIds` (it is written but never returned).
- Unify the `SettingsRules` timeout list with its settings-UI twin (one source).
- Return a defensive copy from `PresetRepository.presets`.
- Replace the magic indices in `BranchSwitcherConfigurable` with named constants.
- Deduplicate consecutive `addHistory` entries (switching to the same preset
  twice currently appends two rows).
- Normalize unknown persisted strings (`dirtyActionFromName`, unknown timeouts)
  instead of silently remapping.
- Stop persisting unknown state strings verbatim in `getState`.
- Drop the 2 s no-op log line the reflog watcher emits on quiet panels.
- Reword the misleading "rollback-skipped" warning (it is not a failure).
- Collapse the duplicate per-path WARN for a single repository.
