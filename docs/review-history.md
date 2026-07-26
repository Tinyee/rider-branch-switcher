# Review History

This document consolidates completed code, test, and architecture reviews.
It records durable project decisions and historical outcomes, not active work.
Current behavior is defined by the code, tests, `ROADMAP.md`, and `CHANGELOG.md`.

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

## 2026-06-20 - Architecture Review

The review confirmed the split between the pure JVM `core` module and the
IntelliJ Platform module.

Completed changes:

- Moved cancellation classification into `core` with platform recognition
  injected from the IntelliJ module.
- Centralized full-preset switching in `SwitchFlowCoordinator`.
- Split service responsibilities so UI orchestration and repository access do
  not leak into core rules.
- Consolidated switch code under the current package structure.
- Replaced implicit mutable pipeline state with explicit state where practical.
- Organized Git capabilities by concern while retaining `GitClient` as the
  test boundary.

The dependency direction remains: platform code may depend on `core`; `core`
must not import IntelliJ or platform UI APIs.

## 2026-07-15 To 2026-07-26 - Full Review And Follow-Up

The final broad review found no unresolved P1 or P2 issue. Follow-up fixes:

- Repositories with no readable HEAD now block before checkout.
- Tool Window and shortcut preflight failures are visible and fail closed.
- Single-submodule switching uses the same write gate, cancellation lifecycle,
  notification, and VCS refresh conventions as full switching.
- Rollback, shortcut, detection, and Git failures produce structured,
  bounded diagnostics.
- Shortcut preset loading no longer falls back to stale cached data.
- An unreachable duplicate rollback implementation was removed.
- Low-value duplicate tests were removed, leaving behavior-focused coverage.
- The local-only statistics feature was removed from source, persistence, UI,
  i18n, tests, and active documentation.

Two P3 architecture observations are accepted:

- Write-operation setup remains specialized at each entry point because the
  workflows have materially different UI and rollback behavior.
- Git cancellation is service-scoped and may also stop concurrent reads. The
  write gate prevents competing writes; a finer-grained cancellation model is
  deferred until a real use case justifies the added complexity.

Validation recorded at the end of this review:

- Clean full suite: 290 tests.
- Core and platform Detekt reports: empty.
- `quickCheck`: passed.
- `git diff --check`: passed.

## Maintenance

Record temporary findings in the relevant issue or pull request. Add to this
file only when a review produces a durable architectural, safety, or testing
decision. Do not use this document as an active status tracker.
