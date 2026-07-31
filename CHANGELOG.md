# Changelog

## [0.7.0] - In development

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

- Split pure domain and switch logic into the `core` JVM module; IntelliJ,
  process, service, workflow, and UI concerns remain in the plugin module.
- Return structured switch status, checkpoint, failures, and immutable state
  from the switch pipeline.
- Separate checkpoint capture and recovery from forward switch execution.
- Split Git capabilities by workflow while retaining `GitClient` as the
  aggregate implementation boundary.
- Give each background write an isolated Git operation session with centralized
  open, cancel, close, and exception handling.
- Split reusable workflows for full-preset switching, single-repository
  switching, and repository-state detection from screen-specific UI.
- Separate preset collection commands from preset list rendering.
- Split checkout orchestration from missing-submodule initialization and branch
  selection, and make background result handling explicit.
- Split derive into explicit preflight, checkpoint, and execution phases, with
  platform cancellation and rollback owned by `DeriveBranchRunner`.
- Separate preset clipboard transfer and current-state creation from collection
  persistence, and clarify the preset editor construction flow.
- Make preset loading non-mutating and serialize preset file access on the I/O
  dispatcher; files are created only by an explicit save.
- Dispatch blocking branch discovery and repository-state Git reads to the I/O
  dispatcher while retaining bounded concurrency.
- Retain and report submodules initialized before a later switch failure or
  cancellation instead of deleting worktrees without a checkpoint.
- Require JDK 21 for IntelliJ Platform 2026.1 builds and CI.

### Fixed

- Preserve the latest stash and checkout state when cancellation or a Git query
  fails in the middle of a switch step.
- Attempt stash restoration even when repository rollback fails.
- Restore the exact checkpoint SHA when the branch name is unchanged, while
  refusing destructive reset when the worktree is dirty.
- Restore detached HEAD state correctly.
- Close background Git operations exactly once.
- Terminate interrupted Git processes promptly and preserve the thread
  interruption signal.
- Correct submodule-row context-menu hit handling.
- Treat non-text clipboard content as an empty preset import with a clear
  message instead of logging `Unicode String`.
- Find shared preset files at any nesting depth up to the Git repository
  boundary.
- Preserve completed switch results when task completion races with
  cancellation, so recovery receives the latest execution state.
- Reset the Tool Window switching indicator even when VCS refresh or result
  presentation fails.

### Removed

- Remove per-preset dirty/fetch/pull overrides before public release; these
  options remain global Settings.
- Remove the experimental quick-switch text field and local telemetry feature
  before public release.

### Quality

- 306 automated tests in 33 classes: 153 core and 153 platform/integration.
- CI runs tests, plugin build, Detekt, structural checks, and Plugin Verifier
  across the supported matrix.
- `quickCheck` enforces module direction, background Git lifecycle, write-lease
  pairing, i18n symmetry, and deprecated lifecycle removal.

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
