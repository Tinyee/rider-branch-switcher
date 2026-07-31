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

## P3: Targeted Maintainability

Defer these changes until a concrete feature or failure needs them:

- Narrow the background-task boundary so workflow code depends on
  `GitOperationSession`, `CancellationHandle`, `ProgressHandle`, and injected
  confirmation callbacks instead of direct IntelliJ `Project`, progress, or
  dialog APIs. Keep the IntelliJ adapter in `platform`, and preserve the
  fresh-session cancellation recovery behavior with focused tests.
- Split `SwitchFlowCoordinator` by side effect when the switch flow next
  changes: execution/write lease/VCS refresh, result presentation/rollback
  notification, and preflight UI should have separate owners while the Tool
  Window and shortcut retain one shared execution path.
- Extract `PresetEditor` draft construction, dirty-state comparison, revert,
  and validation decisions from Swing components into pure logic. Keep Swing
  responsible for rendering and event binding; cover the extracted behavior
  with focused JVM tests.
- Extract Tool Window repository-state subscription, debouncing, stale-result
  filtering, and snapshot delivery into a coordinator only when that lifecycle
  grows further. Do not split visual panels merely to reduce line counts.
- Represent recovery as an inspectable rollback plan before execution.
- Define whether a submodule initialized during a failed switch should remain
  initialized or support an explicit cleanup policy.
- Replace remaining user-visible string failure reasons with richer domain
  errors when retry or diagnostic UI needs structured data.
- Re-evaluate CLI Git versus Git4Idea only when measured compatibility,
  performance, or credential handling justifies the migration cost.

These items do not currently block feature work or the first release.
