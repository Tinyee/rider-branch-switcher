# Roadmap

This file tracks current project status and future work. Delivered release
history belongs in [`../CHANGELOG.md`](../CHANGELOG.md); completed review and
refactoring decisions belong in [`review-history.md`](review-history.md).

## Current Baseline

- Development version: `0.7.0`
- Platform: IntelliJ Platform `2026.1.3`, build range `261` to `261.*`
- Runtime/build JDK: 21
- Default build SDK: IntelliJ IDEA Community
- Compatibility verifier target: Rider
- Tests: 310 tests in 30 classes
  - 159 pure JVM core tests
  - 151 platform and integration tests
  - 4 Kotest property tests included in the totals
  - Manual benchmark excluded
- CI: Ubuntu, Windows, and macOS
- Quality gates: `quickCheck`, structural fixture tests, Detekt, plugin build,
  and Plugin Verifier

The current architecture is documented in
[`ARCHITECTURE.md`](ARCHITECTURE.md).

## Delivered Product Scope

- Project-local branch presets for the main repository and submodules
- Preflight preview with branch, dirty-state, and branch-source diagnostics
- Stash, skip, and force dirty-worktree strategies
- Main-repository update before submodule sync and initialization
- Missing-submodule initialization with optional confirmation
- Structured partial failure and cancellation outcomes
- Checkpoint rollback with branch, SHA, detached HEAD, and stash restoration
- Feature-branch derivation across all preset repositories
- Current-state preset creation, rename, reorder, clipboard import/export, and
  recent-switch undo
- Tool Window, keyboard action, Settings page, notifications, English/Chinese
  localization, and VCS refresh

## Release Priorities

### P1: Prepare The First Public Release

- Run `./gradlew releaseCheck` against the final commit.
- Perform manual smoke tests in Rider:
  - load and save presets
  - switch between two complete presets
  - cancel during an active Git command and verify recovery
  - initialize a missing submodule
  - derive and undo a feature branch
  - verify dark/light themes and Chinese/English text
- Confirm the three README screenshots match the final plugin build.
- Review Marketplace description, license, vendor details, icon, compatibility
  wording, and ZIP contents.
- Publish only after Plugin Verifier and the Rider smoke pass are recorded.

### P2: Compatibility Evidence

IntelliJ IDEA Community is the default build target and Rider is the current
compatibility target. Do not advertise additional IDE families until their
product codes are added to verification and their Tool Window, Settings, and
Git workflows receive a manual smoke test.

Potential order:

1. IntelliJ IDEA Ultimate
2. PyCharm Professional
3. WebStorm
4. CLion

This is evidence work, not an expected code rewrite unless a product-specific
incompatibility is found.

### P3: Targeted Maintainability Improvements

These are deferred until a concrete feature or failure requires them:

- Represent recovery as an inspectable rollback plan before execution.
- Decide whether a submodule initialized during a failed switch should remain
  initialized or support an explicit cleanup policy.
- Replace remaining user-visible string failure reasons with richer domain
  error types when retry or diagnostic UI needs structured data.
- Re-evaluate CLI Git versus Git4Idea only when a measured compatibility,
  performance, or credential-handling problem justifies the migration cost.

None of these items currently blocks normal feature work or the first release.

## Quality Policy

- Prefer behavior-focused tests over increasing test count.
- Keep pure decisions in `core` when they do not require IntelliJ runtime state.
- Keep each background Git operation isolated and cancellation-aware.
- Fail closed when repository state required for a safe write cannot be read.
- Do not hard-reset a dirty worktree during automatic recovery.
- Keep active findings in issues or pull requests; update this file only when a
  priority or project-level decision changes.

## Manual Diagnostics

- `./gradlew benchmark` measures the real-Git 51-repository scenario. It has no
  wall-clock assertion and is intentionally outside normal tests.
- `./gradlew pitestCore` runs scoped mutation testing for pure decision logic.
  It is CPU-heavy and intentionally outside `test` and `releaseCheck`.

## Not Planned

- Reintroducing per-preset dirty/fetch/pull overrides in the main Tool Window
- Restoring removed local telemetry/statistics
- Broad architectural refactoring without a concrete product need
- Advertising unverified IDE families
