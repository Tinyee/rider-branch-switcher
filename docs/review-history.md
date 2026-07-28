# Review History

This document consolidates completed code, test, and architecture reviews.
It records durable project decisions and historical outcomes, not active work.
Current behavior is defined by the code, tests, [architecture](ARCHITECTURE.md),
[roadmap](ROADMAP.md), and [changelog](../CHANGELOG.md).

## 2026-06-08 - UI And Repository Review

The first review covered the Tool Window redesign and then expanded to the
switch pipeline, persistence, concurrency, and IntelliJ integration.

Key findings included:

- Checkout failures could allow later pull or submodule-sync steps to run
  against the wrong branch.
- Dirty-worktree skip and stash failures did not always stop checkout.
- Failed checkout could leave changes in the stash without clear recovery.
- Preset deserialization and import needed explicit validation.
- Task cancellation, EDT updates, stale UI references, and disposed-project
  guards were incomplete.
- Several UI labels, i18n keys, log controls, and lifecycle hooks were
  inconsistent.

These findings drove the later step-result contract, preflight checks,
cancellation lifecycle, persistence validation, and UI cleanup. The original
line-by-line report is superseded by the current implementation and tests.

## 2026-06-09 - Tool Window Product Decisions

The Tool Window was reorganized around a compact operational workflow:

- Global dirty, fetch, pull, timeout, and initialization options live in
  Settings rather than preset cards.
- Preset cards prioritize current state, branch differences, switching, and
  derivation.
- Editing, submodule rows, collection commands, and switch orchestration use
  separate UI collaborators.
- Low-frequency actions use menus, while primary creation and switch actions
  remain visible.

The original wireframes and phased implementation plan remain in Git history.
Current UI behavior is defined by code, tests, and README screenshots.

## 2026-06-13 - Derive-Branch Safety Review

Eight review rounds exercised cancellation, partial failure, checkpointing,
rollback, branch validation, and write concurrency.

The resulting safety rules are:

- Derive must finish preflight and checkpoint creation for every target before
  making changes.
- Missing repositories, dirty worktrees, detached HEAD, unreadable state, and
  failed safety probes block the operation.
- The current branch must match the preset base branch.
- Branch names must pass Git ref validation before execution.
- Cancellation and unexpected exceptions trigger rollback through a separate
  operation lifecycle.
- Rollback isolates failures per repository, removes branches created by the
  operation when safe, and reports incomplete recovery.
- Concurrent write operations are rejected by the service write gate.
- Completion refreshes IDE VCS state and uses localized, structured
  notifications.

Integration tests were expanded to assert final branch, stash, rollback, and
cancellation state rather than only the returned result.

## 2026-06-13 - Test Review

The test review shifted the suite away from test-count growth and toward
observable behavior.

Completed work included:

- Direct cancellation coverage for Git processes and the switch pipeline.
- Task lifecycle, preset migration, asynchronous branch loading, and real Git
  rollback coverage.
- Removal of low-value data-class, `copy()`, and duplicate structure tests.
- A deterministic large-repository call-budget test.
- A manual benchmark task for real timing measurements.
- A narrowly scoped, manual PITest task for pure decision logic.

Wall-clock performance remains a manual benchmark concern and is intentionally
not enforced by ordinary tests.

## 2026-06-19 - Remove Per-preset Switch Options

Per-preset dirty, fetch, and pull overrides were removed before the first public
release. They duplicated global Settings, made preset cards noisy, and made it
unclear which value controlled an operation.

Presets therefore store only identity and branch targets. Dirty strategy,
fetch, pull, timeout, and initialization confirmation remain global settings.
Reintroducing overrides would require a new design that keeps advanced options
out of the primary switching workflow.

## 2026-06-20 - Architecture Review

The review confirmed the split between the pure JVM `core` module and the
IntelliJ Platform module.

The durable decisions were to keep cancellation classification injectable,
centralize full-preset orchestration, keep UI and repository access out of core
rules, and use capability-oriented Git interfaces. Platform code may depend on
`core`; `core` must not import IntelliJ APIs.

## 2026-07-15 To 2026-07-26 - Full Review And Follow-Up

The final broad review found no unresolved P1 or P2 issue. It established
fail-closed handling for unreadable repository state, consistent write
lifecycle and diagnostics across entry points, live preset loading for
shortcuts, and behavior-focused rather than duplicate tests.

Two P3 architecture observations were accepted at that point:

- Write-operation setup remains specialized at each entry point because the
  workflows have materially different UI and rollback behavior.
- Git cancellation is service-scoped and may also stop concurrent reads. The
  write gate prevents competing writes. The later deep refactor replaced this
  limitation with operation-scoped sessions.

The full suite, Detekt, `quickCheck`, and diff validation passed at the end of
the review.

## 2026-07-26 - Deep Architecture Refactor

The deferred architecture work was completed without changing user-facing
switch behavior. The durable decisions were:

- Pass immutable switch state explicitly and return a structured execution
  result.
- Keep checkpoint capture and recovery separate from forward execution.
- Give every background write an isolated Git operation session.
- Centralize mechanical task and process lifecycle in `GitBackgroundRunner`.
- Keep reusable application flows in `workflow/` and screen-specific decisions
  at their UI entry points.
- Enforce package direction and removed lifecycle APIs through `quickCheck`.

The full suite, Detekt, structural fixtures, and plugin ZIP build passed.

## 2026-07-26 - Post-refactor Hardening

Focused follow-up reviews confirmed four recovery invariants:

- Preserve the latest operation state through exceptions and cancellation.
- Attempt repository rollback and stash restoration independently.
- Restore branch and commit state without hard-resetting a dirty worktree.
- Interrupt and close each Git operation exactly once.

The follow-up suite and structural checks passed. User-facing clipboard and
context-menu regressions received focused coverage.

## Maintenance

Record temporary findings in the relevant issue or pull request. Add to this
file only when a review produces a durable architectural, safety, or testing
decision. Do not use this document as an active status tracker.
