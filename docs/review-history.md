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

## 2026-08-21 - Module Design Review

A module-by-module design review of all five package groups (core small
packages, `core/switch`, plugin integration, `ui`, `workflow`). The overall
categorization of the two-module layout held up; findings cluster in four
families: cross-layer string contracts, the same logic implemented twice,
duplicated interface declarations, and dead or falsely-flexible API surface.
Line anchors below are against `main` as of `1e5fdce`.

### Verdicts per package group

- **core small packages** — healthy, with `git` the densest package.
  `GitQuery.kt` is misnamed (its eight interfaces are mostly `*Client`), and
  the Query/Client suffixes carry no read-vs-write meaning. Interfaces are
  redeclared: `localBranchExists`/`remoteBranchExists` on both `SwitchGitClient`
  and `SwitchPreflightGitClient`; `DeriveGitClient`'s `localBranchProbe`/
  `dirtyProbe` are semantic copies of `localBranchExists`/`isDirty`;
  `repositoryIdentity` is overridden identically across three layers.
  `PresetConfig.kt` packs nine types into one file, and the per-entry
  drop/conflict policy exists twice (`PresetLoader.normalizePresetIds` and
  `parsePresetImport`). `BranchNameRulesTest` tests model validation but lives
  in the `switch` test package.
- **`core/switch`** — the pipeline design is strong: Step protocol, immutable
  `SwitchState` accumulator, checkpoint/recovery separation, and the
  at-most-once stash-restore cancellation semantics. Three warts:
  `StepResult.Fatal` (`SwitchStep.kt:21`) has no producer in any of the 29
  files; `SubmoduleTreeStep` (343 lines) is a self-contained sub-pipeline
  (fetch/init/checkout/pull/sync/topology) masked as a single
  `stage = CHECKOUT` step; lock handling is fragmented across
  `IndexLockBlock`, `WriteGuardGitClient`, and `LockBlockedPresentation`, and
  the disable-submodules logic is implemented twice (`SubmoduleSyncStep.kt:48`
  vs `SubmoduleTreeStep.kt:308`).
- **plugin integration** (`git.impl`/`platform`/`service`) — clean boundaries;
  `GitOps` is the facade, `GitCommandClient` the sole CLI implementation of the
  core interfaces, and process ownership holds (`ProcessBuilder` appears only
  here, enforced by ArchUnit). Two P2 items: `GitOps.isGitOnPath()` does an
  unbounded `waitFor()` outside the process runner on the tool-window creation
  path; and `BranchSwitcherService` is a quasi-composition-root (state, write
  gate, git-cache, preset delegation, history) with an asymmetric
  load-`Result`/save-throws error contract.
- **`ui`** — the decision-layer pattern is established but not applied
  consistently. The four preview-table cell renderers inline all
  `PreflightRow -> text/color` decisions (the same class of decision
  `CollisionDecision` now owns), pure decision functions live in three homes
  (`UiRules`, `core/presentation`, and component files such as
  `currentStatePresetBlockReason` and `mergeBranchChoices`), and
  `BranchSwitcherPanel.logDetected` duplicates `UiRules.mainStatusText`.
  Two verified duplications worth fixing: the `createMoreActionsButton` factory
  appears verbatim in two files, and the amber warning color is repeated four
  times. The strongest parts (token-guarded async loads, dual-entry
  `SwitchFlowCoordinator`, EDT discipline) were confirmed good.
- **`workflow`** — the highest-quality package: no god object, dependency
  inversion through constructor injection, four orthogonal concurrency layers.
  `SingleRepositorySwitcher` re-implements the `core/switch` safety gates
  (index.lock checks twice plus dirty/identity gates) instead of reusing
  `findBlockingIndexLocks`, and folds two distinct failure reasons into one
  `NOT_REGISTERED` value. `RepositoryStateDetector.detect()` is production dead
  code (tests only), and `WriteOperationLauncher.afterRelease` is not invoked
  on the exception path.

### Additional findings

The first pass above captured the headline items. These medium and lower
findings from the same review also stand, for completeness:

- **Medium**: the single-repository switch path inlines its result-to-notification
  mapping in `PresetListManager.switchSubmodule` (143-193) instead of going
  through a presenter like the full-preset path's `SwitchResultPresenter`, so
  the two switch paths present results differently.
- **Medium**: reflog-based external-switch polling lives inside
  `BranchSwitcherPanel` (300-340) as self-contained logic whose predicate is
  already extracted (`shouldRunReflogWatch`) — it could be its own watcher.
- **Medium**: `GitOps.inspectPreflight` builds a fresh `GitCommandClient` per
  call (49-55), losing the shared remote cache and escaping `GitOps.cancel()`'s
  scope, contradicting the class comment that direct calls share one
  cancellation scope.
- **Medium**: `BranchSwitcherConfigurable` exposes a subset of the service
  options and omits `autoDiscardMeta` (which the service carries).
- **Medium**: the "reserve one git-process slot for foreground switches" budget
  is duplicated across modules (`RepositoryStateRefreshCoordinator.kt:68` and
  `BranchLoadCoordinator.kt:121`), both keyed to `MAX_CONCURRENT_GIT_PROCESSES`.
- **Medium**: `WriteOperationLauncher` is constructed twice with identical
  configuration (`SwitchController:41`, `SwitchFlowCoordinator:78`).
- **Medium**: cancellation recovery opens a fresh session two different ways
  (`SwitchRunner.recoverSwitch` via `operations.run` vs
  `DeriveBranchRunner.rollbackAfterCancellation` hand-rolled open/close).
- **Medium**: `DeriveNotification.Blocked` derives `indexLockBlockedCount` by
  subtracting it from `skipped` (`DeriveNotification.kt:52-55`), relying on the
  invariant that preflight lock blocks are always `SKIPPED`.
- **Lower**: `toDirtyAction()` silently maps an unknown persisted string to
  `Stash`; `scheduleUi`'s default lambda is duplicated (`BranchComboUtil` vs
  `SubmoduleRowManager`); `listSubmodulePaths` is a projection of
  `registeredSubmodules`; `ResolvedSwitchRequest.resolve` merely forwards
  options; `AppLoggerTest` builds a ~20-method anonymous git fake (a shared
  fake would help); `refreshVcsTail`'s doc says background but it runs
  synchronously; `UiLayout.kt` holds only a subset of the layout primitives.
- **Cosmetic (style-level, recorded for completeness)**: the
  `ModalCancelWatcher` hand-rolled 100 ms polling daemon thread could be an
  `Alarm` or a coroutine loop; `FeedbackIconButton` and
  `FeedbackIconToggleButton` differ only by a `model.isSelected` condition and
  could merge; `GitCommandClient.close()` is identical to `cancel()` while the
  `GitOperationSession` contract promises a distinct resource release;
  `OperationStage.CHECKPOINT` is reused for the index.lock precheck that runs
  after the checkpoint; the cancellation idiom splits between the
  handle+lambda+classifier trio and `SessionCancelGuard` without cross-links.

### Cross-layer finding (highest confidence)

`GitResult.failureKind` (`core/GitTypes.kt:14-25`) classifies failures by
prefix-matching stderr strings emitted by the plugin layer's
`GitProcessRunner` ("cancelled", "timeout after ...", ...). This is the only
cross-layer semantic contract ArchUnit cannot enforce: changing a message
silently degrades cancellation/timeout classification. Two independent review
passes flagged the same defect.

### Durable decisions

- **Fix (high)**: give `GitResult` a structured failure reason (or at minimum
  pin the sentinel prefixes as constants with a locking test); bound
  `isGitOnPath()`; converge the four renderer decisions into `UiRules` with
  tests, matching the `CollisionDecision` refactor.
- **Fix (medium)**: deduplicate the `core/git` interface surface
  (`DeriveGitClient` reusing existing probes); have `SingleRepositorySwitcher`
  reuse `findBlockingIndexLocks` instead of re-checking locks by hand; unify
  the single-repo result presentation with `SwitchResultPresenter`; give
  `GitOps` one shared direct-call scope; add the missing `autoDiscardMeta`
  setting; converge the process-slot budget to one value; share the
  `WriteOperationLauncher` instance.
- **Cheap cleanup**: annotate or remove `StepResult.Fatal`; annotate or remove
  `RepositoryStateDetector.detect()`; make `SwitchFlowCoordinator`'s internal
  steps private; move `BranchNameRulesTest` to the model test package; collect
  the amber warning into a shared color constant and the more-button factory
  into `UiUtil`.
- **Deliberate, no change**: `SubmoduleTreeStep` as a documented sub-pipeline
  (topology traversal does not fit the `StepExecution(state)` protocol); the
  `operation` bridge package; one-interface-per-file in `switch`; the
  `FetchStep`/`PullStep` `ALL` default (production passes only `MAIN` — worth a
  clarifying comment, not a refactor).

These fixes are active work and belong on the roadmap, not tracked here.

## 2026-08-21 - Behavior-Correctness Fix Round

The design-review findings above were triaged by scope: the behavior-correctness
defects (6 High + 16 Medium) plus the two test-coverage gaps were fixed across
six commits, each carrying its regression test; the quality/style Mediums and
all Lows were deliberately deferred rather than churned in the same pass. The
full suite, Detekt, and the plugin ZIP build pass at the end of the round.

Durable decisions from the fixes:

- **Gson reflective serialization and lazy fields.** `@delegate:Transient` is
  the only annotation that excludes a delegated-property lazy field from
  reflection (`@Transient` and `@field:Transient` are both rejected for
  delegates); without it every preset save embeds a `cachedTargets$delegate`
  blob whose content depends on runtime call history.
- **Stash-restore termination is not a lock race, and a user cancel is not a
  timeout.** A terminated apply may have created its own index.lock before
  dying, so termination is judged before the raced-lock branch; only a
  non-terminated failure with a lock present is a true race. An explicit user
  cancel marks the restore `interrupted` and suppresses the automatic
  stash-only retry, while a timeout termination does not.
- **`git stash list` exits 0 when empty**, so a non-zero exit is a genuine query
  failure and fails closed (`GitQueryException`) rather than degrading to the
  stack top and misapplying an external stash. This differs from
  `revParseOptional`'s fail-open, where `--verify refs/stash` exit-1 is the
  normal negative.
- **Git process-slot release is always bounded.** Deferred releases (onExit
  futures and the polling fallback) share one deadline, and every error outcome
  routes through the same deferral: a slot is never held forever by an
  uninterruptible process, and never handed back while that process may still be
  running.
- **Git output paths are not trimmed.** `untrackedFiles`/`targetBranchMatches`
  drop only empty lines; a file literally named `" leading.txt"` must reach
  collision detection intact, and the final stdout trim keeps leading whitespace.

Deferred by explicit scope decision (recorded so the decision survives; these
remain open on the roadmap): the `PresetConfig` nine-type file split,
`AppLoggerTest`'s large anonymous git fake, `PresetLoader.normalizePresetIds`'s
dead `changed` computation, the `SettingsRules` timeout dual-source, the
`remoteName`/`checkedProjects` unbounded caches, `PresetRepository.presets`
defensive copy, `BranchSwitcherConfigurable`'s magic indices, `addHistory`
dedup, unknown-persisted-string normalization, `getState` persisting unknown
strings verbatim, the watcher's 2 s noop log line on quiet panels, the
misleading "rollback-skipped" warning, the duplicate per-path WARN for a single
repo, and every Low finding.

## 2026-08-21 - Module Review Round: core/switch

An exhaustive per-file pass over `core/switch` (29 main files) confirmed the
previous rounds' state-machine hardening and found one remaining
data-safety defect plus three robustness gaps. L4/L5 from the same pass were
verified and deliberately not changed.

Durable decisions from the fixes:

- **Approved collision discards are deleted at the last safe moment, not
  up-front for every repository.** `git stash push -u` sweeps untracked files,
  so a dirty repository under the Stash strategy (the default, and the common
  case for collision files) must have its approved files deleted before the
  stash — otherwise they would be swept into the WIP backup and re-collide with
  the freshly checked-out tracked versions on restore. That pre-delete is a
  deliberate trade-off: if a later gate (a failed main checkout, a topology
  change) skips such a repository, its approved files are already gone. The fix
  moves every *other* approved repository — Force, or clean enough that it is
  never stashed — to a just-in-time delete in `BranchCheckout` right before its
  own checkout write, so a downstream skip preserves their approved files. The
  earlier dirty==Skip guard covered only the Skip strategy; this closes the
  remaining non-stashed skip paths.
- **A lock-probe failure never escapes `execute()`.** `safeBlockingLockIssues`
  now maps any probe failure (query or path-resolution) to a structured
  pre-mutation issue, matching `recordCheckpoint`'s fail-closed catch, so a
  single repository whose index.lock cannot be probed cannot collapse the whole
  switch into a generic failure without recovery.
- **Derive rollback polls cancellation between repositories**, mirroring
  `SwitchRecoveryExecutor`: a user cancel stops the rollback and defers the
  remaining paths instead of relying on the next Git command to throw.

## 2026-08-21 - Module Review Round: src/ui

An exhaustive per-file pass over `src/ui` (26 files) found no hard defects —
the busy-state machine (claim after lease, idempotent release), branch-load
lifecycle, collision-decision purity, and EDT serialization all held up under
re-verification. Two consistency gaps were fixed; four candidates were verified
and deliberately left.

Durable decisions from the fixes:

- **The tool-window switch refreshes state on success.** The keyboard-shortcut
  path publishes `BranchSwitchListener.onBranchSwitched`; the tool-window path
  passed no `onSuccess`, so it refreshed only indirectly via FileStatusManager
  events or the 2 s reflog watch. `SwitchController.runSwitch` now passes
  `onSuccess = onStateChanged`, matching the derive path's explicit refresh.
- **The collision preview's OK button tracks the live discard decision.**
  The button label was computed once from the init-time options. Two fixes make
  it accurate on every toggle: `collisionDecision.needsConfirm` now judges only
  the files the decision actually discards (`only-meta` keeps non-meta files, so
  they no longer force a confirm label), and `SwitchPreviewDialog` recomputes
  the label from the live checkbox state instead of the init-time snapshot.

Verified and not changed: the preflight `ModalCancelWatcher` builds an `Alarm`
on a worker thread (works for `POOLED_THREAD`; a convention note only), the
`PresetCollectionActions` in-progress flag is not reset when the project is
disposed before the EDT callback runs (no further operations can start after
disposal), the rollback busy clears when its job completes rather than after the
post-write presentation (the write is already done, matching the accepted
gate-release window), and the icon flash `Timer` is not cancelled on dispose (a
bounded one-shot with no side effects on a detached button).

## 2026-08-21 - Module Review Round: src/workflow

An exhaustive per-file pass over `src/workflow` (7 files) found no hard defects —
the write-lease lifecycle, recovery/session-freshness, and the refresh
coordinator's generation-based supersession all held under re-verification. Two
path-hygiene gaps from the M15 scope were closed.

Durable decisions from the fixes:

- **The derive operation-start log no longer exposes the absolute project
  path.** Every other `operation started: root=` site was already basenamed
  (SwitchPreflightUi, SwitchRunner, SingleRepositorySwitcher); `DeriveBranchRunner`
  was the only one logging `projectRoot.toAbsolutePath()`. The tool-window log
  is copyable/exportable, so only the directory name reaches it.
- **The single-repo safety gate logs the relative target path, not the absolute
  directory.** `repository is not initialized at <absolutePath>` now uses the
  relative `path`, the same shape as the sibling gates in this file and the
  switch target line.

## 2026-08-21 - Module Review Rounds: src/git/impl, core leaves, src entry layer

The final three rounds of the exhaustive per-file module pass found no hard
defects. Each candidate defect was chased to a deliberate, tested behavior
rather than fixed blindly; the deferred backlog is unchanged.

Durable decisions from the pass:

- **The Force-switch warning treats unknown dirtiness as dirty.**
  `shouldShowForceWarning` counts `dirtyCount != 0`, and a failed probe reports
  -1, so an unprobeable repository still triggers the "switching without
  stashing" warning. Pinned by `SwitchPreviewRulesTest`. Force is destructive;
  warning on unknown state is fail-closed, and narrowing the check to `> 0`
  would let a Force switch run silent over a possibly-dirty repository.
- **`SessionCancelGuard` is race-free by construction.** `attach` and `cancel`
  both use set-then-check / set-then-get on atomics, so every interleaving ends
  with the attached session cancelled; `detach` compare-and-sets so a stale
  caller never detaches a newer session.
- **`GitOperationResult.Failed` models business failures only.** The runner
  catches cancellation and `RuntimeException`; an `Error` deliberately escapes
  so a programming defect surfaces loudly instead of being downgraded to a
  `Failed` result. The `TaskBridge` Throwable capture is the complementary half
  of the same contract.
- **The post-mutation VCS refresh tail never runs on the EDT.** All three
  callers invoke `refreshVcsTail` from `WriteOperationLauncher.afterRelease`,
  which runs on `Dispatchers.IO` after the write lease is released; the UI hop
  goes through `uiLater`. The synchronous refresh is safe by construction.
- **A preset save conflict refuses to clobber external edits.** Digest-based
  change detection in `PresetRepository` throws `PresetFileChangedException`,
  which the UI turns into a reload prompt; a best-effort `.bak` preserves
  entries dropped as invalid at load.
- **The cross-layer stderr contract is closed.** The sentinel prefixes are
  pinned as constants in `GitTypes.kt` and locked by `GitResultTest`
  ("classifies every sentinel stderr via the shared constants"), so
  cancellation/timeout classification cannot silently drift on either side.

Known deferred items (unbounded remote caches, the `PresetConfig` nine-type
split, `addHistory` dedup, unknown-persisted-string normalization, and the
recorded Lows) remain open on the roadmap, unchanged.

## Maintenance

Record temporary findings in the relevant issue or pull request. Add to this
file only when a review produces a durable architectural, safety, or testing
decision. Do not use this document as an active status tracker.
