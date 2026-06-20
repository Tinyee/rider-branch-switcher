# Architecture Review - 2026-06-20

## Current State

- `core/`: pure JVM module - `GitClient` facets, model, switch pipeline, rules, import/export.
- `src/`: IntelliJ Platform module - adapters, UI, notifications, persistence, workflow orchestration.

The split is correct. The 2026-06-20 architecture cleanup is now complete; only future feature-driven refactors should reopen these areas.

## Completed P2 Items

### 1. CancellationClassifier -> core

Problem: core had platform cancellation checks by class-name string.

Result: `CancellationClassifier` lives in core, and the IntelliJ module injects platform cancellation recognition.

### 2. SwitchFlowCoordinator -> platform/UI orchestration

Problem: `SwitchController` and `SwitchPresetAction` duplicated switch flow pieces: preflight, confirmation, notification, telemetry/history, and VCS refresh.

Result: `SwitchFlowCoordinator` centralizes shared switch orchestration while entry points keep their UI-specific selection/preview behavior.

### 3. BranchSwitcherService split

Problem: the project service held telemetry, preset cache, write gate, history, and settings directly.

Result: `BranchSwitcherService` is closer to a composition root. Preset file state lives in `PresetRepository`; telemetry state/export/prompt lives in `TelemetryService`.

### 4. Root switch package rename

Problem: root-module `switch/` contained IntelliJ adapters and looked like pure switch pipeline code.

Result: platform switch glue moved to `com.submodule.branchswitcher.platform`; pure switch pipeline remains under `core/.../switch`.

### 5. Deprecated telemetry bridge removal

Problem: plugin-unreleased compatibility bridges kept old telemetry API shape alive.

Result: internal call sites use `service.telemetry.*`; deprecated bridge accessors were removed.

## Completed P3 Items

### 6. SwitchContext -> explicit pipeline state

Problem: top-level mutable `SwitchContext` collections (`stashedPaths`, `skippedPaths`, `successfulCheckouts`) made cross-step coupling implicit.

Result: `SwitchPipelineState` owns cross-step state behind explicit methods such as `markSkipped`, `trackStash`, `markCheckoutSuccessful`, and `checkoutSucceeded`.

### 7. GitClient split by concern

Problem: `GitClient` exposed query, write, stash, submodule, derive, and lifecycle operations as one large interface.

Result: the core API now defines focused facets: `GitQueryClient`, `GitWorkingTreeClient`, `GitBranchClient`, `GitSubmoduleClient`, and `GitOperationLifecycle`. `GitClient` remains as the aggregate interface for full Git implementations. Read-only paths such as preflight and branch-combo loading use `GitQueryClient`.

## Validation Evidence

- `./gradlew :core:compileKotlin :core:compileTestKotlin compileKotlin compileTestKotlin --max-workers=1 --no-parallel`: PASS
- `./gradlew :core:test --tests "com.submodule.branchswitcher.switch.SwitchStepTest" --tests "com.submodule.branchswitcher.switch.SwitchExecutorTest" --tests "com.submodule.branchswitcher.switch.SwitchPreflightTest" --max-workers=1 --no-parallel`: PASS
- `./gradlew quickCheck --max-workers=1 --no-parallel`: PASS
