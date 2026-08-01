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

## 2026-07-31 - Persistence And Lifecycle Hardening

A focused reliability pass completed six previously deferred items:

- Preset lookup now walks to the Git repository boundary without a fixed depth
  limit.
- Preset loading is non-mutating; explicit saves create files and persist
  in-memory ID normalization. Repository load/save operations are serialized and
  filesystem access runs off the UI thread.
- Blocking branch and repository-state Git reads run on the I/O dispatcher while
  preserving bounded concurrency and UI-thread-only rendering.
- Git task completion and cancellation use one atomic outcome state, preserving
  completed execution data required by recovery in either race ordering.
- Switch UI cleanup is idempotent and runs after presentation errors or
  background refresh failures.
- A submodule initialized during a later failed or cancelled switch is retained,
  recorded in execution state, and reported in recovery logs and localized
  notifications. Automatic deletion was rejected because no pre-switch
  checkpoint exists and the new worktree may contain valuable data.

Focused regression tests cover deep preset lookup, non-mutating persistence,
serialized repository access, task race ordering, UI cleanup, and cancellation
immediately after submodule initialization.

## 2026-08-01 - Git Resource And Read-path Hardening

Three remaining Git read-path risks were resolved while preserving fresh safety
checks around mutations:

- Git processes share bounded admission and dedicated stream-drain capacity.
  Stdout overflow is a structured failure, while stderr retains a bounded tail.
- Branch discovery owns an isolated cancellable Git session. Hidden, removed,
  and superseded editors stop obsolete work, and generation tokens reject stale
  UI delivery.
- Repository-state refresh uses one porcelain-v2 status process per repository.
  Preflight batches status, HEAD, dirty count, and exact target refs in at most
  three first-time processes per repository and remains fail-closed.
- Switch execution, checkpoint capture, and recovery continue to perform fresh
  reads next to mutation and do not consume display-oriented snapshots.

Real CLI integration tests enforce linear process budgets across five
repositories, and focused tests cover output bounds, stream ownership, direct
Git cancellation, stale-result rejection, and fail-closed batch failures.

## 2026-08-01 - Targeted Maintainability Boundaries

Three deferred boundaries were completed without changing switch behavior:

- Preset draft construction, dirty comparison, and rename classification are
  pure core rules; `PresetEditor` retains Swing rendering and event binding.
- `workflow/` depends on a pure `GitOperationRunner` contract and injected
  cancellation and confirmation policies. IntelliJ task, progress, and dialog
  APIs remain in `platform/` and `ui/`, enforced by `quickCheck`.
- Switch preflight UI, write/VCS execution, and result presentation have
  separate owners while both entry points retain one shared coordinator.

Workflow tests now assert business outcomes through a deterministic operation
boundary. Platform tests separately assert real background session open,
cancel, close, preserved completion, and exception conversion contracts.
The full `releaseCheck` passed after the boundary changes.

## 2026-08-01 - Responsive Tool Window Layout

Narrow Tool Windows previously clipped controls because the preset scroll
content retained its preferred width beyond the viewport. Layout ownership was
centralized in the plugin UI layer:

- Scroll content now tracks the rendered viewport width and never relies on a
  hidden horizontal extent.
- Two-region action and form rows stack according to measured component sizes,
  not a locale-specific fixed breakpoint.
- Command buttons retain their native preferred width; only branch form fields
  expand into remaining space, with an explicit maximum width.
- Preset headers preserve the primary and overflow actions while collapsing the
  secondary derive action into the existing menu.
- Provisional sizing can conservatively stack from the narrowest laid-out
  component/ancestor width, but it never demotes the last rendered stacked
  height. Only `doLayout()` changes the mode from the assigned row width, then
  schedules one ancestor revalidation. This prevents delayed hidden-resize
  events and stale `BoxLayout` caches from clipping reopened footer actions.
- Action allocation shrinks primary controls before the overflow trigger, and
  recomputes secondary visibility when the primary action is shown or hidden.
- Footer commands use nested responsive rows. At extreme widths Discard and
  Save stack explicitly, so `FlowLayout` cannot wrap Save into a clipped line.
- The current preset omits its unavailable switch action instead of reserving
  space for a disabled button.
- Overflow commands use one shared Swing popup that preserves IntelliJ
  Look-and-Feel and keyboard behavior while owning the approved width,
  grouping, icon spacing, destructive tone, and right-edge anchoring.
- Main and submodule branch rows share one responsive form layout.

The approved wide, compact, narrow, and expanded-menu states are retained in
the interactive [`design/branch-switcher-ui-v1.html`](design/branch-switcher-ui-v1.html)
reference so later maintenance can compare behavior without treating the mockup
as a second implementation specification.

The former pure Boolean width rule and its synthetic test were removed. Swing
layout tests now verify viewport width adoption, hidden-row reopening, stacked
component bounds, and overflow-action priority directly.

## Maintenance

Record temporary findings in the relevant issue or pull request. Add to this
file only when a review produces a durable architectural, safety, or testing
decision. Do not use this document as an active status tracker.
