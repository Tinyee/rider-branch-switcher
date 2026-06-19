# AI Shared Review

## Review Scope

- Date: 2026-06-20
- Target: review local fix for GitHub CI `quickCheck` failure after `SwitchFlowCoordinator` extraction
- Result: `PASS` - the pushed CI failure and follow-up fallback UI-state issue are fixed locally

## Active Findings


### ARCH-02 - P2 - BranchSwitcherService is a broad service object

- Status: `OPEN`
- Evidence:
  - `src/main/kotlin/com/submodule/branchswitcher/service/BranchSwitcherService.kt`
- Impact: The service owns persistent options, preset repository state, `GitClient` lifecycle, write gate, branch-detection generation, history, telemetry, and switch request resolution. This is manageable now but will keep growing as settings, telemetry, and per-preset options expand.
- Suggested fix: split high-churn responsibilities into smaller collaborators, starting with `PresetRepository`, `TelemetryStore`, and `OperationGate`; keep `BranchSwitcherService` as the project-level composition root.
- Verification: migrate in small steps with `BranchSwitcherServiceTest` coverage preserved; run service tests and compile.

### ARCH-04 - P3 - SwitchContext carries mutable cross-step state

- Status: `OPEN`
- Evidence:
  - `core/src/main/kotlin/com/submodule/branchswitcher/switch/SwitchStep.kt`
- Impact: `stashedPaths`, `skippedPaths`, and `successfulCheckouts` are shared mutable state between steps. This is acceptable for the current small pipeline, but new steps can create hidden coupling.
- Suggested fix: consider an explicit `SwitchPipelineState` returned/updated by each step when the pipeline grows further.
- Verification: pipeline tests should assert state transitions and side effects, not only final success.

### ARCH-05 - P3 - GitClient is becoming a large interface

- Status: `OPEN`
- Evidence:
  - `core/src/main/kotlin/com/submodule/branchswitcher/git/GitClient.kt`
- Impact: The interface covers query, write, stash, submodule, derive, and operation lifecycle concerns. Test doubles must implement a wide surface area.
- Suggested fix: defer until churn justifies it, then split into query/write/submodule/lifecycle facets or provide focused fake builders.
- Verification: keep existing `GitClient` fake coverage and `GitOpsTest` passing during any split.

## Verified In This Review

- `ARCH-01` verified fixed: `SwitchFlowCoordinator` moved to `ui/`, so `switch/` no longer imports `Messages` or any UI package.
- `ARCH-01B` verified fixed for busy, cancelled, and normal result paths: `SwitchFlowCoordinator.executeAndNotify` now accepts `onFinished`, and calls it on busy rejection, cancellation, and final success/failure handling.
- `ARCH-01C` verified fixed: preflight-error fallback now sets `setSwitchInProgress(true)` and passes `onFinished = { setSwitchInProgress(false) }`, matching the normal preflight-confirmed path.
- `ARCH-03` remains addressed by earlier core cleanup commits; no new core/platform leak was introduced by this CI fix.

## Positive Architecture Notes

- `SwitchFlowCoordinator` is still the right architectural direction for reducing drift between ToolWindow and action entry points.
- Keeping the coordinator in `ui/` makes the package boundary honest: it owns dialogs, notifications, telemetry dispatch, UI refresh, and platform-facing flow orchestration.
- `switch/` remains focused on switch execution pipeline primitives and no longer depends on UI APIs.

## Validation

- `./gradlew quickCheck --max-workers=1 --no-parallel`: PASS
- `./gradlew compileKotlin --max-workers=1 --no-parallel`: PASS
- `git diff --check origin/main..HEAD`: PASS
- `git status -sb`: `## main...origin/main [ahead 2]`
