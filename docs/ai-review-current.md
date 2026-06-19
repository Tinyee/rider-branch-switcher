# AI Shared Review

## Review Scope

- Date: 2026-06-20
- Target: architecture review follow-up after `SwitchFlowCoordinator` extraction
- Result: `FAIL_BLOCKING_REVIEW` - shared flow direction is good, but the latest commit introduces boundary and UI-state regressions

## Active Findings

### ARCH-01 - P1 - SwitchFlowCoordinator extraction violates package boundary and quickCheck

- Status: `OPEN`
- Evidence:
  - `src/main/kotlin/com/submodule/branchswitcher/switch/SwitchFlowCoordinator.kt`
  - `./gradlew quickCheck --max-workers=1 --no-parallel`: FAIL
- Impact: `SwitchFlowCoordinator` is a platform/UI coordinator but currently lives under the `switch` package and imports `com.intellij.openapi.ui.Messages`. The repository rule treats `switch/` as the switch pipeline boundary, so quickCheck fails with `switch/ imports ui/: [import com.intellij.openapi.ui.Messages]`. The extraction direction is correct, but the coordinator belongs in a platform/UI-facing package, not the switch pipeline package.
- Suggested fix: move `SwitchFlowCoordinator` out of `src/main/kotlin/com/submodule/branchswitcher/switch/`, for example to `ui/SwitchFlowCoordinator.kt` or a new `flow/` / `workflow/` package, then update imports in `SwitchController` and `SwitchPresetAction`.
- Verification: rerun `./gradlew quickCheck --max-workers=1 --no-parallel` and `git diff --check HEAD~1..HEAD`.

### ARCH-01B - P1 - ToolWindow progress icon can remain stuck after failed/cancelled/busy switch

- Status: `OPEN`
- Evidence:
  - `src/main/kotlin/com/submodule/branchswitcher/ui/SwitchController.kt`
  - `src/main/kotlin/com/submodule/branchswitcher/switch/SwitchFlowCoordinator.kt`
- Impact: Before the refactor, `SwitchController.executeSwitch()` called `setSwitchInProgress(false)` before branching on `cancelled`, `ok`, or failure. After the refactor, `SwitchController.runSwitch()` calls `setSwitchInProgress(true)` before `coordinator.executeAndNotify(...)`, but only resets it in the success callback. Failed switches, cancelled switches, and `tryStartWrite()` busy failures can leave the ToolWindow icon in the in-progress state.
- Suggested fix: add an `onFinished` callback to `executeAndNotify` and call it for success, failure, cancellation, and busy-gate rejection; or let `SwitchController` wrap the whole call in a local state guard that always resets the icon.
- Verification: add a coordinator/controller test or at least grep-reviewed callback coverage; run `compileKotlin` and relevant switch tests.

### ARCH-02 - P2 - BranchSwitcherService is a broad service object

- Status: `OPEN`
- Evidence:
  - `src/main/kotlin/com/submodule/branchswitcher/service/BranchSwitcherService.kt`
- Impact: The service owns persistent options, preset repository state, `GitClient` lifecycle, write gate, branch-detection generation, history, telemetry, and switch request resolution. This is manageable now but will keep growing as settings, telemetry, and per-preset options expand.
- Suggested fix: split high-churn responsibilities into smaller collaborators, starting with `PresetRepository`, `TelemetryStore`, and `OperationGate`; keep `BranchSwitcherService` as the project-level composition root.
- Verification: migrate in small steps with `BranchSwitcherServiceTest` coverage preserved; run service tests and compile.

### ARCH-03 - P2 - Core platform wording mostly fixed; verify docs stay clean

- Status: `FIXED_PENDING_REVIEW`
- Evidence:
  - `03c0082 fix: clean up core KDoc - remove IntelliJ type references`
  - `19ce561 refactor: introduce CancellationClassifier to eliminate platform leak in core`
- Impact: Runtime class-name checks were replaced by `CancellationClassifier`, and the follow-up commit removed direct IntelliJ KDoc references from core. This appears to address the original ARCH-03 concern.
- Suggested fix: keep this as pending until a final scan confirms no `com.intellij` references remain in `core/src/main`.
- Verification: run `rg -n "com\\.intellij|ProcessCanceledException|ProgressIndicator" core/src/main` and targeted switch preflight tests.

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

## Positive Architecture Notes

- `SwitchFlowCoordinator` is the right architectural direction for reducing drift between ToolWindow and action entry points.
- `SwitchController` and `SwitchPresetAction` are now thinner and easier to read.
- Shared preflight, force warning, switch execution, notification, telemetry, and refresh are much closer to one path.

## Validation

- `./gradlew quickCheck --max-workers=1 --no-parallel`: FAIL (`switch/ imports ui/: [import com.intellij.openapi.ui.Messages]`)
- `./gradlew compileKotlin --max-workers=1 --no-parallel`: PASS
- `git diff --check HEAD~1..HEAD`: PASS
