# AI Shared Review

## Review Scope

- Date: 2026-06-20
- Target: architecture review of current project structure
- Result: `PASS_WITH_RECOMMENDATIONS` - no blocking architecture defect found; follow-up refactors recommended
- Validation level: static architecture review only; no Gradle test run for this documentation update

## Active Findings

### ARCH-01 - P2 - Switch orchestration is duplicated across ToolWindow and action entry points

- Status: `OPEN`
- Evidence:
  - `src/main/kotlin/com/submodule/branchswitcher/ui/SwitchController.kt`
  - `src/main/kotlin/com/submodule/branchswitcher/action/SwitchPresetAction.kt`
  - `src/main/kotlin/com/submodule/branchswitcher/switch/SwitchRunner.kt`
- Impact: `SwitchRunner` centralizes the execution lifecycle, but preflight, confirmation, notification, telemetry/history, and VCS refresh still exist in separate ToolWindow/action flows. Future UI or safety changes can drift between entry points.
- Suggested fix: introduce a platform-layer `SwitchFlowCoordinator` for `preflight -> confirm -> run -> notify -> refresh -> telemetry/history`; keep `SwitchController` and `SwitchPresetAction` as thin entry adapters.
- Verification: add/adjust `SwitchRunnerTest` or coordinator tests for both entry modes; run targeted switch/controller/action tests plus `quickCheck`.

### ARCH-02 - P2 - BranchSwitcherService is a broad service object

- Status: `OPEN`
- Evidence:
  - `src/main/kotlin/com/submodule/branchswitcher/service/BranchSwitcherService.kt`
- Impact: The service owns persistent options, preset repository state, `GitClient` lifecycle, write gate, branch-detection generation, history, telemetry, and switch request resolution. This is manageable now but will keep growing as settings, telemetry, and per-preset options expand.
- Suggested fix: split high-churn responsibilities into smaller collaborators, starting with `PresetRepository`, `TelemetryStore`, and `OperationGate`; keep `BranchSwitcherService` as the project-level composition root.
- Verification: migrate in small steps with `BranchSwitcherServiceTest` coverage preserved; run service tests and compile.

### ARCH-03 - P2 - Core still contains platform cancellation/progress wording

- Status: `PARTIALLY_FIXED`
- Evidence:
  - `core/src/main/kotlin/com/submodule/branchswitcher/switch/ProgressHandle.kt`
  - `core/src/main/kotlin/com/submodule/branchswitcher/switch/SwitchPreflight.kt`
  - `core/src/main/kotlin/com/submodule/branchswitcher/switch/DeriveBranchExecutor.kt`
- Impact: `19ce561` introduced `CancellationClassifier` and platform injection, so the runtime class-name check has been removed. However core KDoc still directly references IntelliJ types in `CancellationClassifier.kt` and `ProgressHandle.kt`, so the conceptual platform leak is not fully closed.
- Suggested fix: update core KDoc to use platform-neutral wording, e.g. "platform cancellation exception" and "platform progress indicator", without direct `com.intellij.*` links.
- Verification: `./gradlew quickCheck --max-workers=1 --no-parallel` PASS; `./gradlew :core:test --tests "com.submodule.branchswitcher.switch.SwitchPreflightTest" --rerun-tasks --max-workers=1 --no-parallel` PASS.

### DOC-01 - P3 - architecture review document has trailing whitespace

- Status: `OPEN`
- Evidence:
  - `docs/architecture-review-2026-06-20.md`
- Impact: `git diff --check HEAD~1..HEAD` fails on a trailing whitespace line. This is not a runtime issue, but it breaks the repository whitespace gate.
- Suggested fix: remove trailing spaces from the `VCS refresh` bullet.
- Verification: rerun `git diff --check HEAD~1..HEAD`.

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

- `:core` is a useful boundary: switch/derive/preflight/model/rules are now pure JVM and much cheaper to test.
- `GitClient` abstraction makes real Git integration and fake-based tests practical.
- `SwitchExecutor` step pipeline is easier to reason about than a monolithic switch function.
- `ResolvedSwitchRequest` prevents entry points from bypassing effective option resolution.
- `SwitchRunner` is already a good anchor for further platform-flow consolidation.

## Validation

- `./gradlew quickCheck --max-workers=1 --no-parallel`: PASS
- `./gradlew :core:test --tests "com.submodule.branchswitcher.switch.SwitchPreflightTest" --rerun-tasks --max-workers=1 --no-parallel`: PASS
- `git diff --check HEAD~1..HEAD`: FAIL (`docs/architecture-review-2026-06-20.md` trailing whitespace)
