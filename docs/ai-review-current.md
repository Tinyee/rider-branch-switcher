# AI Shared Review

## Review Scope

- Date: 2026-06-20
- Target: review local BranchSwitcherService split into TelemetryStore and PresetRepository
- Result: `FAIL_BLOCKING_REVIEW` - extraction direction is good, but detekt currently fails on a leftover private constant

## Active Findings

### SVC-01 - P1 - BranchSwitcherService keeps unused PLUGIN_VERSION_FALLBACK after telemetry extraction

- Status: `OPEN`
- Evidence:
  - `src/main/kotlin/com/submodule/branchswitcher/service/BranchSwitcherService.kt:206`
  - `src/main/kotlin/com/submodule/branchswitcher/service/TelemetryStore.kt:87`
  - `./gradlew detekt --max-workers=1 --no-parallel`: FAIL, `UnusedPrivateProperty` on `BranchSwitcherService.kt:206`
- Impact: `TelemetryStore` now owns plugin-version export fallback, but `BranchSwitcherService` still has the old private `PLUGIN_VERSION_FALLBACK`. This is dead code and blocks the normal detekt gate.
- Suggested fix: remove the unused `companion object` constant from `BranchSwitcherService` if it only contains `PLUGIN_VERSION_FALLBACK`. Keep the fallback constant in `TelemetryStore`.
- Verification: rerun `./gradlew detekt --max-workers=1 --no-parallel` and `./gradlew quickCheck --max-workers=1 --no-parallel`.

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

- `ARCH-02` implementation direction is sound: telemetry and preset persistence have been extracted into `TelemetryStore` and `PresetRepository`, leaving `BranchSwitcherService` closer to a project-level composition root.
- `PresetRepository` preserves the previous load/save cache semantics: successful load stores both path and parsed file; first save resolves/creates the preset file before saving.
- `TelemetryStore` keeps the existing opt-in behavior: counters only increment when opted in, install ID is generated only after opt-in, and export still redacts the ID prefix.
- Existing service-level compatibility accessors still compile against current call sites.

## Validation

- `./gradlew quickCheck --max-workers=1 --no-parallel`: PASS
- `./gradlew compileKotlin --max-workers=1 --no-parallel`: PASS
- `./gradlew :test --tests "com.submodule.branchswitcher.service.BranchSwitcherServiceTest" --max-workers=1 --no-parallel`: PASS (task up-to-date)
- `./gradlew detekt --max-workers=1 --no-parallel`: FAIL (`UnusedPrivateProperty` at `BranchSwitcherService.kt:206`)
