# Review History

This document consolidates completed code, test, and architecture reviews.
It records durable project decisions and historical outcomes, not active work.
Current behavior is defined by the code, tests, [architecture](ARCHITECTURE.md),
[roadmap](ROADMAP.md), and [changelog](../CHANGELOG.md).

## 2026-08-13 - Dead Code Removal And Retention

The over-engineering audit's cleanup removed the `remoteUrl` capability end to
end: the `CheckpointEntry.remoteUrl` field, the `GitRepositoryQuery` and
`SwitchGitClient` interface methods, the `GitCommandClient` implementation, the
checkpoint `git remote get-url` call, and the test-double overrides. The checkpoint
now records only the `.gitmodules`-declared URL and logs its fingerprint as
`declaredId`. Eleven unreferenced i18n keys were removed from both bundles, and the
empty `verification-2026-07-31 at 19.17.09/` directory was deleted.

Two items initially listed as dead were verified and retained:

- `DelegatingGitOperationSession` is the default `GitClient.openOperation()`
  implementation; test doubles reach it via `super.openOperation()`. Removing it
  would force `openOperation()` to be abstract across many fakes for no
  maintainability gain.
- `docs/design/branch-switcher-ui-v1.html` is referenced by four documents as the
  retained design reference; deleting it would leave dangling links.

Durable decisions: a symbol reachable only from test doubles is test
infrastructure, not dead code to delete; a documented design reference is an
asset, not a dead file.

## 2026-08-13 - Remote-Change Gate Baseline And Coverage Follow-up

The submodule remote-change gate compared the checkpoint's live-config remote
against the post-`submodule sync` config. That both depended on sync ordering and
falsely blocked a user's local fork override (`.gitmodules` unchanged), after sync
had already clobbered the override. It now compares the `.gitmodules`-declared URL
recorded in the checkpoint against the current topology, decoupling the check from
sync and live config. The same-path repository-replacement integration test still
passes unchanged, confirming the protection is intact.

Three previously uncovered decision points gained direct tests:

- `sanitizeDiagnosticText` redaction is now pinned against credential leakage
  (URI remote, SCP remote, and bare/bearer secret assignments).
- `isUnassociatedSubmoduleWorktree` and `expectedSubmoduleGitDirectory` now cover
  the associated/unassociated predicate and top-level/nested git-directory
  resolution, including the fail-closed cases (null identity, missing superproject
  root, git-directory mismatch).
- The core `CancellationClassifier.DEFAULT` and `rethrowIfCancellation` are tested
  directly, complementing the platform classifier tests.

Durable decisions: the remote-change gate answers "did this branch switch change
the submodule's declared URL", not "did the live config remote change"; a
legitimate migration (same repository, new URL) still fails closed, because
distinguishing it from a replacement would require contacting the new remote — the
exact action the gate exists to prevent. Submodule topology membership
(`isUnregistered`) stays covered through the tree-step path rather than a dedicated
trivial test.

## 2026-08-13 - Test Quality Review

A follow-up review of the test suite found two coverage gaps and four false-positive
tests. All were addressed:

- The index-lock checkpoint fast path (a File-stat on the recorded repository id)
  gained a positive on-disk-lock test; that deciding branch previously had no test
  with a real lock present, so a path-resolution bug would silently defeat the
  lock defense.
- `isSubmoduleOnlyDirty` is now verified true against a real submodule-only porcelain
  status; the prior GitOps test only asserted the false (untracked) case.
- A vacuous "staged gitlink" unit test, whose fake could not model a gitlink, was
  removed; the classification is pinned by the parser test and a real-git integration
  test.
- The checkpoint test now uses a stateful fake, proving the checkpoint is recorded
  before the first checkout rather than after.
- The IntelliJ `ProcessCanceledException` branch of the platform cancellation
  classifier gained a direct test.

Durable decisions: a test that cannot model the behavior it names is removed rather
than kept for coverage count; call-count assertions are kept only when paired with a
companion behavior assertion.

## 2026-08-13 - Failed-Switch Stash Recovery Review

A follow-up review of the previous round's fixes found that a failed
(non-cancelled) switch left stashes created by the dirty-handling step unrestored:
recovery only ran on cancellation, and the forward-path restore (PullStep) was
skipped when an earlier step threw. A second pass then verified two
exception-handling gaps in the initial fix.

Fixed with regression tests:

- A failed switch restores its own stashes before returning, contained behind a
  structured boundary so a restore failure cannot lose the execution result,
  checkpoint, or stash state.
- A skipped parent submodule disables its nested descendants, matching every other
  skip path in the traversal.
- A stash restore blocked by an `index.lock` remains retryable on both lock paths:
  the write guard throwing `IndexLockBlockedException`, and `stashApply` returning
  a failure after the guard's check. Non-lock failures keep at-most-once semantics.
- Recovery's detached-SHA fallback verifies the named branch and reports
  `RECOVERY_FAILED` rather than a false `RESTORED`.
- New preset names are validated as Git branch names before the dialog accepts them.

Durable decisions: stash restore distinguishes a lock race (safe to retry, no Git
apply started) from an apply failure (at-most-once, the worktree may be partially
modified); the failed-switch restore reuses the same structured containment as
cancellation recovery instead of letting an exception escape `execute()`.

## 2026-08-12 - Switch Pipeline And Cancellation Review

A follow-up review of the switch pipeline and cancellation found 9 findings,
extended by an incremental pass with 4 more. All were fixed with regression tests:

- The dirty strategy is no longer bypassed when a repository is already on the
  target branch: with Stash selected, a dirty repo already in place is still
  stashed before its post-checkout pull instead of being pulled unprotected. The
  review's parallel "Skip is bypassed" claim did not reproduce — Skip already
  marks on-target dirty repos skipped.
- The check-then-act `index.lock` window is closed on every remaining write path:
  derive branch creation and rollback, single-repository checkout, and recovery
  checkout/reset now re-check immediately before each write and report a
  structured `INDEX_LOCK_BLOCKING` instead of a generic checkout/reset mystery.
- Checkpoint and lock-query failures during a switch are contained as structured
  results (`GIT_QUERY_FAILED` / `CHECKPOINT_UNAVAILABLE`) with the repository
  path, instead of escaping `execute()` and losing the execution result. A
  cancelled or interrupted probe is rethrown as cancellation rather than
  downgraded to a `FAILED` result.
- Preflight runs against an isolated, cancellable Git session; a modal-cancel
  watcher terminates the in-flight probe within one poll interval instead of
  letting it run until its timeout.
- Branch discovery reserves one global Git-process slot for foreground switches
  and recovery (concurrency capped at 3 instead of 4).
- The main-reflog watch re-arms itself when the git directory is temporarily
  unresolvable instead of stopping permanently until a panel hide/show.
- A repository probe error is reported as its own warning, and `branchMissing`
  excludes probe errors so the UI no longer reports "branch not found" for an
  unreadable repository.
- Dirty handling reuses the batch inspection's repository fact and drops a
  redundant `git rev-parse` per target.
- Rollback notification base text and detail are separated so localized
  (Chinese) messages do not glue to the first detail line.

The incremental pass verified the checkpoint/lock-probe cancellation
classification, re-classified a stash restore whose `stashApply` races a newly
created `index.lock` as `INDEX_LOCK_BLOCKING`, wrapped the derive lock probes so
a probe failure is `PREFLIGHT_FAILED`/`GIT_QUERY_FAILED` rather than a generic
branch-creation failure, and made the modal-cancel watcher read its stop flag
directly. The full suite, Detekt, and `quickCheck` pass at the end of the review.

## 2026-08-12 - Audit Regressions Review

A multi-angle review of the index-lock defense, state-refresh, and UI feedback
work found 14 verified findings (several reproduced empirically against real
git). All were fixed with regression tests:

- A staged submodule gitlink (`1 M. S...`) is no longer misclassified as
  submodule-only dirt: it enters the stash flow, and when `git stash` reports
  "no local changes to save" (verified: git cannot stash gitlink-only changes),
  the switch fails closed and skips the target, preserving the staged pointer
  instead of aborting a checkout midway.
- `WriteGuardGitClient` rechecks for `index.lock` immediately before every
  mutating git write, closing the check-then-act gap between the executor
  preflight and the first mutation.
- The lock probe resolves the git directory directly on disk — zero process
  spawns on the common no-lock path, reusing the checkpoint's repository
  identity — and a probe failure is surfaced as a structured `GIT_QUERY_FAILED`
  rather than silently passing or escaping `execute()`.
- Dirty and submodule-only classification share one porcelain-v2
  `--untracked-files=normal` query, so very large untracked trees cannot blow
  past the output cap.
- Process termination waits for the whole descendant tree (a nested git writer
  keeps its full grace window to remove its own `index.lock`), and Windows skips
  the SIGTERM phase since `Process.destroy()` is `TerminateProcess`.
- The reflog watch pauses while the panel is hidden and re-arms on re-show; a
  debounced `FileStatusManager` listener restores refresh for in-IDE edits and
  staging, which the reflog stamp cannot see.
- A repeated feedback flash restores the pre-first-flash icon and tooltip;
  stash-restore locks report `INDEX_LOCK_BLOCKING` so the balloon localizes the
  lock path; notification details separate lock lines from the retained notice;
  refresh probes reserve one git-process slot for foreground switches.

Durable decisions: the preflight trusts a checkpoint-resolved repository
identity unless the git directory is gone, trading a tiny stale-identity risk
for zero process spawns; a failed lock probe never fabricates an
`INDEX_LOCK_BLOCKING` path it did not observe.

## 2026-08-11 - Index Lock And Termination Hardening

Completed P3-10 and extended the stale-index.lock defense in depth:

- `GitProcessRunner.terminateProcess` now signals git process trees gracefully:
  SIGTERM first, a bounded 1.5 s cooperative-exit window, then SIGKILL only for
  processes still alive. A git write process can remove its own `index.lock`
  before dying, closing the remaining leak source (a write force-killed between
  creating `index.lock` and writing the index previously left a stale 0-byte
  lock that silently blocked later writes). Descendant shutdown paths were
  validated and are covered by focused tests.
- `findBlockingIndexLocks` is extracted into a shared core check, and the
  actionable `INDEX_LOCK_BLOCKING` preflight now also guards the derive-branch
  preflight, the single-repository checkout, and recovery's first write
  (checkout/reset and stash apply), instead of surfacing checkout/reset
  "File exists" mysteries.
- The plugin still never auto-deletes a lock it might not own; it reports the
  exact path and asks the user to remove it when no other git process is running.

## 2026-08-04 - Safety, Recovery, And Diagnostics Review

Ten prioritized findings were resolved in one maintenance pass:

- Git command diagnostics sanitize URI/SCP remotes and credential-like values.
- Successful submodule initialization must leave a directory, usable Git
  repository, and repository identity before checkout can continue.
- Write-facing Git capability interfaces no longer hide unsafe optional
  defaults; probe failures fail closed.
- `quickCheck` rejects any IntelliJ API reference in `workflow/`, including
  fully qualified references, and Detekt now enforces pragmatic complexity
  limits with an empty baseline.
- Switch and derive outcomes use structured stage/code/path issues instead of
  parallel string maps.
- Preflight, execute, refresh, and recovery share one phased operation context.
- Repository-state refresh owns a cancellable operation session and suppresses
  superseded delivery.
- Recovery builds an inspectable plan, revalidates identity before every write,
  blocks dirty hard resets, verifies final HEAD, and reports per-path outcomes.
- The Tool Window diagnostics gained timestamps, latest-operation filtering and
  copying, clear, and complete-log access.
- Product wording now claims Rider 2025.1+ verification while describing other
  IntelliJ IDE families as not yet product-certified.

The long dirty-handling, derive-preflight, and submodule-traversal methods were
split along domain phases rather than exempted from the new complexity rules.

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
- A manual benchmark task for real timing measurements, later removed when its
  unregistered-repository scenario no longer matched switch semantics.
- A narrowly scoped, manual PITest task for pure decision logic.

Large-repository performance is now protected by deterministic Git call-budget
and real process-budget tests. Environment-dependent wall-clock thresholds are
intentionally not enforced.

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
