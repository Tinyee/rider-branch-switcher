# Changelog

## [0.8.0] - In development

### Added

- Update the main repository before submodule sync and initialization, allowing
  a submodule added only on the remote parent branch to be initialized and
  switched in the same operation.
- Recursively initialize missing submodules, with an optional confirmation
  setting.
- Block current-state preset creation when a required repository has no
  readable branch, preventing incomplete presets.
- Add focused recovery tests for cancellation, stash restoration, detached
  HEAD, same-branch SHA drift, dirty reset protection, and real Git rollback.
- Add a Chinese architecture and Kotlin code-reading guide for new
  contributors.

### Changed

- A staged submodule gitlink update is treated as stashable dirt, not
  submodule-only: if `git stash` cannot save it, the switch fails closed and
  skips the target rather than aborting a checkout midway.
- Every mutating git write (stash, fetch, checkout, pull, submodule) rechecks for
  a pre-existing `index.lock` immediately before starting, closing the
  check-then-act gap between the initial preflight and the first mutation.
- The `index.lock` probe resolves the git directory directly on disk (zero
  process spawns on the common no-lock path), and a probe failure is reported as
  a structured `GIT_QUERY_FAILED` instead of silently passing the preflight.
- Dirty and submodule-only classification share one
  `git status --porcelain=v2 --untracked-files=normal` query, bounding untracked
  enumeration so very large untracked trees cannot blow past the output cap.
- Git process termination waits for the whole descendant tree to exit after
  SIGTERM (a nested git writer keeps its full grace window to remove its own
  `index.lock`), and on Windows skips the fake cooperative-exit phase because
  `Process.destroy()` is `TerminateProcess`, not a signal.
- A stash-restore blocked by `index.lock` reports `INDEX_LOCK_BLOCKING`, so the
  notification balloon localizes the actionable lock path.
- State-refresh probes reserve one git-process slot for a foreground switch
  (refresh concurrency capped at 3 instead of 4).
- Notification details separate lock lines from the retained-stash notice with a
  newline instead of gluing them together.
- A repeated log-copy feedback flash restores the icon and tooltip from before
  the first flash rather than from the previous flash.
- Cancelled repository-state probes are classified as cancellation instead of
  failures, removing noisy `[detect] ... failed` log lines when a refresh is
  superseded.
- Log-header icon buttons now show hover and pressed feedback, the filter
  toggle shows its selected state, and copying the operation log briefly
  flashes a confirmation checkmark.
- A switch now proceeds when the only dirty changes are submodule-related
  (`git stash` ignores submodules, so there is nothing a superproject stash can
  protect); a submodule checked out at a different commit no longer aborts the
  whole operation.
- A failed stash now reports a pre-existing `.git/index.lock` as an actionable
  hint instead of failing silently with `exit 1` and no stderr.
- Repository status probes run with `git --no-optional-locks` so a refresh never
  writes the index, removing a source of stale `index.lock` files left behind by
  cancelled git processes.
- A switch pre-flights every target repository for a pre-existing git
  `index.lock` and fails up front with an explicit message naming the blocked
  repository instead of surfacing a checkout mystery failure.
- The same stale-`index.lock` preflight now guards the derive preflight, the
  single-repository switch, recovery rollback, and stash restore, so every
  write workflow names the exact blocked repository instead of a checkout,
  reset, or stash-apply "File exists" failure.
- Cancelled or timed-out git processes are now terminated gracefully (SIGTERM
  first with a short cooperative window, SIGKILL only as a fallback), letting a
  killed write remove its own `index.lock` instead of leaking a stale lock that
  silently blocks later writes.
- Localize the stale-`index.lock` guidance shown in the notification balloons
  (English and Chinese); the Tool Window log diagnostics remain English.
- The tool window no longer probes git on every file-status change; it refreshes
  only on switch, panel show, manual reload, and detected external git
  operations (a cheap main-reflog file-stamp watch, no git process). A debounced
  `FileStatusManager` listener restores refresh for in-IDE edits and staging,
  which the reflog stamp cannot see, and the watch pauses while the panel is
  hidden and re-arms on re-show.

- Missing-submodule initialization is confirmed once, upfront, before the switch
  starts (instead of prompting on the background thread mid-run). When the
  confirmation setting is enabled, a nested submodule that is only discovered as
  missing during execution is now declined rather than prompted.
- Preset saves refuse to overwrite a preset file that was modified outside the
  IDE since it was loaded, and offer a reload action instead.
- Branch lists retry loading after a transient Git failure when the preset
  editor is collapsed and re-expanded.
- Split pure domain and switch logic into the `core` JVM module; IntelliJ,
  process, service, workflow, and UI concerns remain in the plugin module.
- Return structured switch status, checkpoint, issue codes, and immutable state
  from the switch pipeline.
- Separate checkpoint capture and recovery from forward switch execution.
- Split Git capabilities by workflow while retaining `GitClient` as the
  aggregate implementation boundary.
- Give each background write an isolated Git operation session with centralized
  open, cancel, close, and exception handling.
- Split reusable workflows for full-preset switching, single-repository
  switching, and repository-state detection from screen-specific UI.
- Isolate workflow orchestration from IntelliJ APIs behind an injected
  `GitOperationRunner`, progress handles, cancellation policy, and confirmation
  callbacks.
- Separate switch preflight dialogs, write/VCS execution, and result
  presentation while retaining one Tool Window and shortcut execution path.
- Separate preset collection commands from preset list rendering.
- Move preset draft construction, dirty comparison, and rename decisions into
  pure core rules while keeping Swing rendering in `PresetEditor`.
- Split checkout orchestration from missing-submodule initialization and branch
  selection, and make background result handling explicit.
- Process nested submodules parent-first, including parent pull and sync before
  child discovery and initialization, and block child mutations when the main
  checkout or required sync fails.
- Share current `.gitmodules` registration checks across full-preset,
  single-repository, and derive writes so retained obsolete worktrees cannot be
  modified accidentally.
- Verify initialized submodule worktree ownership before writes and persist the
  canonical Git-directory identity in switch and derive checkpoints, preventing
  recovery from modifying a replacement repository at the same path.
- Parse `.gitmodules` through Git's null-delimited config output and retain
  section/parent identity, rejecting swapped submodule paths and same-path
  repository URL replacements before fetch or checkout.
- Split derive into explicit preflight, checkpoint, and execution phases, with
  platform cancellation and rollback owned by `DeriveBranchRunner`.
- Separate preset clipboard transfer and current-state creation from collection
  persistence, and clarify the preset editor construction flow.
- Make preset loading non-mutating and serialize preset file access on the I/O
  dispatcher; files are created only by an explicit save.
- Dispatch blocking branch discovery and repository-state Git reads to the I/O
  dispatcher while retaining bounded concurrency.
- Batch repository-state and preflight metadata reads, reducing real Git CLI
  process use to one process per repository for state refresh and at most three
  for first-time preflight without reusing snapshots in write or recovery steps.
- Retain and report submodules initialized before a later switch failure or
  cancellation instead of deleting worktrees without a checkpoint.
- Compile against the IntelliJ Platform 2025.1 API baseline with Java 21 and
  Kotlin 2.1 API compatibility; verify every supported Rider platform branch
  in CI without packaging a newer Kotlin standard library.
- Remove duplicate and non-behavioral tests, strengthen previously vacuous
  assertions, add focused failure-path coverage, and drop the single-use
  Kotest dependency.
- Make Tool Window content follow the visible viewport width and centralize
  responsive action, form-row, and overflow layouts for narrow sidebars.
- Align preset headers and compact actions with the approved Tool Window
  design, omit the redundant current-preset switch action, and render overflow
  commands through a shared, theme-aware overflow popup.
- Preserve compact controls under extreme widths, elide long branch and preset
  text with full-value tooltips, and allow narrow preflight tables to scroll
  horizontally instead of crushing their columns.
- Ignore hidden regions during responsive measurement and clamp inset and
  stacked-indent placement when a Tool Window is narrower than its padding.
- Explain each global switch setting in context and clarify the actual behavior
  of dirty-worktree strategies, Git timeouts, fetch, pull, and submodule init.
- Rename the history action to "Switch to previous preset" and the former
  "Force" UI strategy to "Switch without stashing", matching their real
  non-destructive behavior.
- Clarify personal preset overrides, team-shared root presets, the single main
  repository topology, and the plugin's no-telemetry policy before Marketplace
  publication.
- Persist all Tool Window log levels to `idea.log`, correlate write workflows
  with operation IDs, retain exception stack traces, and record bounded runtime,
  request, checkpoint, recovery, VCS refresh, and final-result diagnostics.
- Keep preflight, execution, refresh, and recovery under one phased operation
  context; sanitize Git remotes and credential-like diagnostic values.
- Model recovery as an inspectable plan with per-repository outcomes,
  execution-time safety checks, and HEAD postcondition verification.
- Give repository-state refresh an isolated cancellable Git session and reject
  superseded snapshots at final UI delivery.
- Add timestamps, latest-operation filter/copy, clear, and full `idea.log`
  actions to the bounded Tool Window diagnostics.
- Enable Detekt limits for method length, nesting depth, and cyclomatic
  complexity, then split the existing switch/derive hotspots to comply.
- The dirty strategy now applies uniformly even when a repository is already on
  the target branch: with "stash" selected, a dirty repo already in place is
  stashed before its post-checkout pull instead of being pulled unprotected.
- Checkpoint and lock-query failures during a switch are contained as structured
  results (`GIT_QUERY_FAILED` / `CHECKPOINT_UNAVAILABLE`) with the repository
  path instead of escaping the workflow; cancelled or interrupted probes are
  treated as cancellation, not failures.
- Switch preflight runs in an isolated cancellable Git session; cancelling the
  check terminates the in-flight probe promptly instead of letting it run until
  its timeout.
- Branch discovery reserves one global Git-process slot for foreground switches
  and recovery (concurrency capped at 3 instead of 4).
- The main-reflog watch re-arms when the git directory is temporarily
  unresolvable instead of stopping until a panel hide/show.
- A repository probe error is shown as its own warning instead of being
  misreported as a missing branch, and the preview summary excludes probe-error
  rows from both pending-switch and missing-branch counts.
- Dirty handling reuses the batch inspection's repository fact and drops a
  redundant `git rev-parse` per target.
- Rollback notification base text and detail are separated so localized messages
  do not glue to the first detail line.
- The submodule remote-change gate compares the `.gitmodules`-declared URL recorded
  at checkpoint against the current topology, instead of the live config after
  `submodule sync`; a local fork override no longer falsely blocks a switch, while
  same-path repository replacement is still rejected.

### Fixed

- Preserve the latest stash and checkout state when cancellation or a Git query
  fails in the middle of a switch step.
- A stale `index.lock` created after the initial guard now blocks derive branch
  creation and rollback, single-repository checkout, and recovery writes
  (checkout/reset) with a structured `INDEX_LOCK_BLOCKING` instead of a generic
  "File exists"-style failure.
- A stash restore whose `stashApply` races a newly created `index.lock` reports
  `INDEX_LOCK_BLOCKING` with the exact lock path instead of a generic apply
  failure.
- A derive `index.lock` probe that itself fails (process capacity, start failure)
  is classified as `PREFLIGHT_FAILED`/`GIT_QUERY_FAILED` instead of being
  downgraded to a generic branch-creation failure.
- Attempt stash restoration even when repository rollback fails.
- Restore the exact checkpoint SHA when the branch name is unchanged, while
  refusing destructive reset when the worktree is dirty.
- Restore detached HEAD state correctly.
- Close background Git operations exactly once.
- Terminate interrupted Git processes promptly and preserve the thread
  interruption signal.
- Bound Git stdout capture, retain only a bounded stderr diagnostic tail, and
  drain process streams through dedicated capacity instead of the common pool.
- Report Git output-capture failures explicitly instead of continuing with
  silently empty command output.
- Cancel branch discovery and its Git operation when an editor is hidden,
  removed, or superseded, and reject stale results at UI delivery.
- Correct submodule-row context-menu hit handling.
- Treat non-text clipboard content as an empty preset import with a clear
  message instead of logging `Unicode String`.
- Find shared preset files at any nesting depth up to the Git repository
  boundary.
- Preserve completed switch results when task completion races with
  cancellation, so recovery receives the latest execution state.
- Reset the Tool Window switching indicator even when VCS refresh or result
  presentation fails.
- Prevent narrow Tool Windows from clipping strategy text, preset actions,
  branch selectors, and save controls beyond the right edge.
- Preserve stacked footer height when a preset is reopened after a narrow
  Tool Window resize, keep overflow actions visible during action changes, and
  stack Add Submodule, Discard, and Save on one left baseline before any
  control can be clipped.
- Keep top-level and footer actions inside their parent bounds even below the
  Tool Window's normal minimum width, and limit an expanded log to one third of
  the available height.
- Skip preset paths that no longer appear in the target branch's `.gitmodules`
  after the main checkout, while retaining obsolete worktrees to avoid data loss.
- A failed switch restores stashes created by its own dirty-handling step before
  returning, so uncommitted work is no longer left hidden in `refs/stash` when a
  later step throws.
- A skipped parent submodule disables its nested descendants, so a repository the
  user chose to skip is not mutated through a nested child checkout or pull.
- A stash restore whose apply is blocked by an `index.lock` stays retryable, both
  when the write guard throws before Git starts and when Git returns the lock
  failure, instead of permanently abandoning auto-restore.
- Recovery no longer reports `RESTORED` when a branch checkout fell back to a
  detached SHA checkout; it verifies the named branch and reports
  `RECOVERY_FAILED` instead.
- A new preset's name is validated as a Git branch name up front, so names such as
  `HEAD` or `My Feature` cannot be accepted as the main branch and then fail every
  save.

### Removed

- Remove per-preset dirty/fetch/pull overrides before public release; these
  options remain global Settings.
- Remove the experimental quick-switch text field and local telemetry feature
  before public release.
- Remove the stale manual 51-repository wall-clock benchmark; deterministic Git
  call-budget and real process-budget tests retain large-project coverage.

### Quality

- Behavior-focused tests cover core, platform, and real Git CLI integration.
- CI runs tests, plugin build, Detekt, structural checks, and Plugin Verifier
  across the supported matrix.
- `quickCheck` enforces module direction, background Git lifecycle, write-lease
  pairing, i18n symmetry, and deprecated lifecycle removal.
- Positive on-disk-lock, real submodule-only-status, and `ProcessCanceledException`
  classifier tests close previously mock-only paths; vacuous tests that could not
  model what they claimed were removed.
- Direct tests cover the credential-redaction sanitizer, submodule worktree
  association and expected git-directory resolution, and the core cancellation
  classifier default.

## [0.6.0] - 2026-06-13

### Added

- Stable preset IDs with automatic migration of legacy JSON.
- Cancellable Git commands and cancellation-aware background tasks.
- Structured logging for the Tool Window and IntelliJ diagnostic log.
- Settings page, persistent recent history, and first-run guidance.
- Scoped mutation testing, large-repository call-budget coverage, and manual
  benchmark tasks.

### Changed

- Centralized button construction and localized user-facing messages.
- Extracted pure preset import, branch-choice, settings, and UI decision rules.
- Made Git process startup injectable for deterministic lifecycle tests.

## [0.5.0] - 2026-06-07

### Added

- Settings configurable under Version Control.
- Persistent switch history and undo.
- Dynamic remote-name detection.
- Shortcut preflight warnings.
- Preset rename validation and clipboard import/export.

### Changed

- Split the original Tool Window class into panel, switch controller, preset
  list, preset editor, and submodule row responsibilities.
- Unified asynchronous IDE work behind coroutine and TaskBridge adapters.
- Organized production code into domain-oriented packages.

## [0.4.0] - 2026-06-06

### Added

- Automatic restoration of stashes created during switching.
- Per-repository progress display.
- `Ctrl+Alt+B` preset switch action.
- Feature-branch derivation across the main repository and submodules.
- Submodule context menu, preset reordering, and recent-switch undo.
- Project message-bus notifications for switch completion.

## [0.3.0] - 2026-06-05

### Added

- Main-branch difference labels on preset cards.
- Checkpoint-based rollback after partial failure.
- Configurable Git timeout and cancellable switch steps.
- Persistent dirty, fetch, pull, and timeout settings.

## [0.2.0] - 2026-06-04

### Added

- Preflight preview for current and target branches, dirty state, and branch
  source.
- Submodule sync and initialization.
- Preset creation from current repository state.
- IDE notifications, current-preset highlighting, theme-aware colors, and
  IntelliJ native icons.

## [0.1.0] - 2026-06-02

### Added

- Project-local JSON presets.
- One-click switching for a main repository and its submodules.
- Filterable branch selectors.
- Basic dirty, fetch, and pull options.
