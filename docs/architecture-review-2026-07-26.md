# Architecture Review - 2026-07-26

## Scope

- Reviewed commit `b95112f` and the follow-up cleanup in this review.
- Focus: core/platform boundaries, switch entry points, write lifecycle, UI ownership, and testability.

## Assessment

The architecture remains appropriate for the plugin's size. The pure `core` module has no IntelliJ or UI imports; the Platform module owns task/progress adapters, Git CLI implementation, persistence, and Swing UI. `SwitchExecutor` remains a testable sequential pipeline, while `SwitchFlowCoordinator` centralizes the full-preset switch lifecycle shared by ToolWindow and shortcut entry points.

## Fixed P2

### ARCH-01 - VERIFIED - duplicate, unreachable rollback implementation

- Evidence: `SwitchController.rollbackSwitch` had no callers, while the rollback action created by `SwitchFlowCoordinator.executeAndNotify` used its own implementation.
- Risk: future rollback fixes could land in one copy but not the live path.
- Resolution: removed the unreachable controller method. Notification-triggered rollback now has one authoritative implementation in `SwitchFlowCoordinator`.
- Validation: `compileKotlin`, `detekt`, `quickCheck`, and `git diff --check` passed.

## Deferred P3

### ARCH-02 - ACCEPTED - write-operation scaffolding is intentionally specialized

- Full preset switch, single-submodule switch, derive, and rollback each have distinct confirmation, rollback, refresh, and notification semantics.
- A generic `WriteOperationRunner` would reduce repeated lifecycle scaffolding, but would also obscure those behavioral differences and require broad test migration.
- Keep the current explicit paths. Reconsider only if a fourth substantial write workflow is added, or if lifecycle changes begin to repeat across two or more existing paths.

### ARCH-03 - ACCEPTED - shared Git operation cancellation also affects concurrent reads

- `GitOps` is shared by branch-combo/current-state reads and guarded writes. A write cancellation can temporarily make a concurrent read return a cancelled result.
- This is fail-safe and transient; the UI uses later refreshes/reloads, so it is not a data-integrity risk.
- Revisit only if users report branch lists or state indicators intermittently clearing during cancellation. The future design would use a separate read-only Git client or a scoped operation token.

## Follow-up Fixes

### ARCH-04 - VERIFIED - shortcut preset load failure was ignored

- Evidence: `SwitchPresetAction` invoked `loadPresets()` then immediately read the cached list without checking the `Result`.
- Risk: a read failure could be presented as an empty preset list, or allow a stale cached list to be used.
- Resolution: the shortcut now shows the existing preset-load error notification and returns before selection when loading fails.

### ARCH-05 - VERIFIED - diagnostic formatting was incomplete on edge write paths

- Evidence: rollback, stash, and single-submodule switch failures still logged raw or first-line stderr after `GitResult.diagnostic()` had been introduced.
- Resolution: all remaining write-failure logs now use the bounded diagnostic, including failure kind, command, and exit code.

## Review Rounds

1. Module dependency direction: PASS.
2. Core pipeline and mutable state ownership: PASS.
3. Git abstraction boundaries and raw process ownership: PASS.
4. Full-preset entry point convergence: PASS.
5. Write-gate and cancellation lifecycle pairing: PASS after ARCH-01 cleanup.
6. UI component responsibility and callback ownership: PASS; `PresetEditor` delegates writes upward.
7. Testability and verification boundaries: PASS; core rules remain JVM-testable and platform glue is isolated.

## Validation

- `./gradlew compileKotlin detekt --rerun-tasks --max-workers=2 --no-parallel`: PASS.
- `./gradlew :core:test test :core:detekt detekt --rerun-tasks --max-workers=2 --no-parallel`: PASS (288 tests).
- `./gradlew quickCheck --max-workers=1 --no-parallel`: PASS.
- `git diff --check`: PASS.
