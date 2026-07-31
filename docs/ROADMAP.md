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

## P2: Expand Compatibility Evidence

The unified IntelliJ IDEA distribution is the primary build target and Rider
is the current compatibility target. Follow the evidence requirements in the
[support matrix](SETUP.md#support-matrix-policy) before advertising another IDE
family.

Candidate verification order:

1. PyCharm Professional
2. WebStorm
3. CLion

## P2: Correctness And Scale

### P2-01: Remove The Preset Lookup Depth Limit

`PresetLoader.resolveFile()` documents an upward search to the Git boundary but
currently stops after six parent directories. Remove that arbitrary limit so
deeply nested projects consistently find a shared parent preset file, and add a
regression test beyond six levels.

### P2-02: Make Preset Loading Non-mutating And Off The EDT

Opening the Tool Window or shortcut currently performs synchronous preset file
I/O and may create or migrate `.idea/branch-presets.json`. Separate
non-mutating loading from explicit first-write initialization, perform disk
access on an I/O dispatcher, and serialize load/save updates so UI state only
changes after the latest successful load.

### P2-03: Make Git Task Outcome Handoff Atomic

`GitBackgroundRunner` shares completion and cancellation state between the
background task, cancellation callback, and awaiting coroutine. Make that
handoff atomic so a cancellation racing with a completed switch cannot discard
the execution result needed for recovery. Add a deterministic race test.

### P2-04: Reduce Per-repository Git Process Fan-out

Repository-state refresh and preflight each spawn several Git commands per
repository. Add a narrow batch inspection capability that returns branch,
dirty, HEAD, and branch-ref state together where practical; preserve
fail-closed preflight behavior and verify real CLI process budgets for large
projects.

## P3: Targeted Maintainability

This is the complete active P3 inventory. Apply an item only when a feature,
bug, or measured limitation makes the change worthwhile. Historical P3
observations that were resolved by the July architecture refactor remain in
[`review-history.md`](review-history.md).

### P3-01: Complete Workflow Platform Isolation

`SwitchRunner` and `DeriveBranchRunner` still directly use IntelliJ project,
progress, and dialog APIs. When the operation boundary next changes, make
workflow code depend on `GitOperationSession`, `CancellationHandle`,
`ProgressHandle`, and injected confirmation callbacks instead. Keep the
IntelliJ adapter in `platform`, and preserve fresh-session cancellation
recovery with focused tests.

### P3-02: Split Switch Flow Side Effects

`SwitchFlowCoordinator` owns execution/write-lease/VCS refresh, result
presentation and rollback notifications, and preflight UI. When the switch
flow next changes, give those side effects separate owners while retaining one
shared execution path for the Tool Window and shortcut.

### P3-03: Extract Preset Draft Rules

`PresetEditor` combines Swing rendering with draft construction, dirty-state
comparison, revert behavior, and validation. Move those decisions into pure
logic when preset editing grows again; leave the Swing class responsible for
rendering and event binding, with focused JVM tests for the extracted rules.

### P3-04: Isolate Tool Window State Refresh

`BranchSwitcherPanel` owns repository-state subscriptions, debounce timing,
stale-result filtering, and snapshot delivery. Extract a coordinator only when
that lifecycle grows further; do not split visual panels merely to reduce line
counts.

### P3-05: Model Recovery As A Plan

Recovery executes directly from checkpoints and tracked stashes. Introduce an
inspectable rollback plan before execution only if recovery needs preview,
retry selection, audit output, or additional recovery strategies.

### P3-06: Define Failed-Init Cleanup Policy

The switch flow may initialize a missing submodule before a later failure.
Decide explicitly whether that initialization is retained or whether a
user-visible cleanup option is required, then encode and test the chosen
policy.

### P3-07: Use Structured Recoverable Diagnostics

Some recoverable failures still cross layer boundaries as user-visible strings.
Replace them with richer domain errors when retry actions, diagnostics, or
localized presentation need structured data.

### P3-08: Re-evaluate The Git Backend With Evidence

The plugin uses the Git CLI. Consider a Git4Idea migration only after measured
evidence identifies a compatibility, performance, or credential-handling
limitation that the CLI implementation cannot reasonably address.

### P3-09: Dispatch Blocking Git Reads As I/O

Branch discovery and repository-state refresh run synchronous CLI Git commands
through the project coroutine scope. Move the blocking work to an I/O
dispatcher at the call boundary while retaining the existing concurrency limit
and UI-thread-only rendering.

### P3-10: Guarantee Switch UI Cleanup Around Refresh

The Tool Window's switching indicator is reset through the completion callback
after VCS refresh and result presentation. Make cleanup unconditional so VCS
refresh or presentation failures cannot leave the window displaying an active
switch, and add a focused lifecycle test.

### P3-11: Bound Git Process Output And Isolate Stream Draining

`GitProcessRunner` currently reads complete stdout and stderr through the
common future pool. Retain a bounded diagnostic tail instead, and use a
dedicated bounded executor for stream draining so verbose Git failures cannot
consume unbounded memory or shared worker capacity.

### P3-12: Cancel Obsolete Branch Discovery

Branch-combo loads continue after their editor row is removed, hidden, or
superseded; their result is merely ignored at UI delivery. Track and cancel
the per-row load job when it becomes obsolete so visible rows do not wait
behind stale discovery work.

These items do not currently block feature work or the first release.
