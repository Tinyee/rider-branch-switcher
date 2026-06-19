# AI Shared Review

## Review Scope

- Date: 2026-06-20
- Target: full architecture/readability review after service split and switch-flow extraction
- Result: `PASS_WITH_RECOMMENDATIONS` - no blocking architecture defect found; remaining items are incremental cleanup/refactor opportunities

## Active Findings

### ARCH-06 - P2 - Platform switch adapters live under a package name that looks like pure switch logic

- Status: `OPEN`
- Evidence:
  - `src/main/kotlin/com/submodule/branchswitcher/switch/SwitchRunner.kt:25`
  - `src/main/kotlin/com/submodule/branchswitcher/switch/SwitchAdapters.kt:35`
  - `core/src/main/kotlin/com/submodule/branchswitcher/switch/SwitchStep.kt:18`
- Impact: The real pure switch pipeline is in `core/.../switch`, while root `src/main/.../switch` contains IntelliJ adapters (`ProgressIndicator`, VCS refresh, `ProcessCanceledException`, dialogs). This is currently allowed by `quickCheck`, but the package name can mislead future agents into putting platform code near pure pipeline code again.
- Suggested fix: when convenient, move root-module platform switch files to a clearer package such as `platform`, `workflow`, or `ui.flow`, leaving `core/.../switch` as the only package named `switch` for pure pipeline logic. This is a rename/refactor, not urgent.
- Verification: `quickCheck`, `compileKotlin`, and focused switch/action/controller tests after package rename.

### ARCH-07 - P2 - TelemetryStore mixes state storage with platform UI/environment access

- Status: `OPEN`
- Evidence:
  - `src/main/kotlin/com/submodule/branchswitcher/service/TelemetryStore.kt:13`
  - `src/main/kotlin/com/submodule/branchswitcher/service/TelemetryStore.kt:65`
  - `src/main/kotlin/com/submodule/branchswitcher/service/TelemetryStore.kt:77`
- Impact: `TelemetryStore` now owns persisted telemetry counters, opt-in prompt UI, Rider version lookup, plugin metadata lookup, and JSON export formatting. It is still small, but it is more of a telemetry service than a pure store, which makes future testing and platform decoupling slightly harder.
- Suggested fix: either rename it to `TelemetryService` to match the current responsibility, or split later into `TelemetryStore` (state/counters), `TelemetryEnvironment` (plugin/Rider version), and `TelemetryPrompt` (dialog). Given current size, rename or defer is enough.
- Verification: keep `BranchSwitcherServiceTest` telemetry behavior coverage; add direct tests only if splitting environment/export decisions.

### ARCH-08 - P3 - BranchSwitcherService still exposes deprecated telemetry bridge API despite plugin not being released

- Status: `OPEN`
- Evidence:
  - `src/main/kotlin/com/submodule/branchswitcher/service/BranchSwitcherService.kt:115`
  - `src/main/kotlin/com/submodule/branchswitcher/service/BranchSwitcherService.kt:135`
  - Current call sites still use `service.incrementSwitchCount()` / `service.incrementCreateCount()` via function-typed properties.
- Impact: The compatibility layer keeps old API shape alive and introduces unusual function-valued properties for counter increments. Since the plugin is not released yet, keeping deprecated bridges may add more confusion than compatibility value.
- Suggested fix: migrate internal call sites to `service.telemetry.incrementSwitch()` / `incrementCreate()` / `incrementDerive()` / `incrementError()`, then remove deprecated telemetry bridge accessors before release. If you want a facade, use ordinary methods rather than function-valued properties.
- Verification: `compileKotlin`, `BranchSwitcherServiceTest`, and grep for old bridge names.

### ARCH-09 - P3 - BranchSwitcherService KDoc is stale after responsibility split

- Status: `OPEN`
- Evidence:
  - `src/main/kotlin/com/submodule/branchswitcher/service/BranchSwitcherService.kt:27`
  - `src/main/kotlin/com/submodule/branchswitcher/service/BranchSwitcherService.kt:30`
- Impact: The class comment still says the service directly manages preset loading/saving via `PresetLoader` and caches `currentBranches`, but preset persistence is delegated to `PresetRepository`, and branch state is held by UI/editor detection flow. This is not runtime risk, but it weakens the file as architecture documentation.
- Suggested fix: update KDoc to describe the service as a composition root for options, write gate, history, detect generation, telemetry, preset repository, and GitClient factory.
- Verification: documentation-only; `git diff --check` is enough.

### ARCH-04 - P3 - SwitchContext carries mutable cross-step state

- Status: `OPEN`
- Evidence:
  - `core/src/main/kotlin/com/submodule/branchswitcher/switch/SwitchStep.kt:18`
- Impact: `stashedPaths`, `skippedPaths`, and `successfulCheckouts` are shared mutable state between steps. This is acceptable for the current small pipeline, but new steps can create hidden coupling.
- Suggested fix: consider an explicit `SwitchPipelineState` returned/updated by each step when the pipeline grows further.
- Verification: pipeline tests should assert state transitions and side effects, not only final success.

### ARCH-05 - P3 - GitClient is becoming a large interface

- Status: `OPEN`
- Evidence:
  - `core/src/main/kotlin/com/submodule/branchswitcher/git/GitClient.kt:29`
- Impact: The interface covers query, write, stash, submodule, derive, and operation lifecycle concerns. Test doubles must implement a wide surface area.
- Suggested fix: defer until churn justifies it, then split into query/write/submodule/lifecycle facets or provide focused fake builders.
- Verification: keep existing `GitClient` fake coverage and `GitOpsTest` passing during any split.

## Verified In This Review

- No blocking architecture issue found in the current clean worktree.
- `BranchSwitcherService` split improved direction: `PresetRepository` owns preset file cache/load/save, `TelemetryStore` owns telemetry state/export/prompt, and the service is closer to a project-level composition root.
- `SwitchFlowCoordinator` remains the right direction for unifying ToolWindow and shortcut action switch execution.
- `core/` remains free of direct IntelliJ imports in the reviewed boundaries; platform integrations are in the root module.

## Validation

- `git status -sb`: `## main...origin/main`
- `./gradlew quickCheck --max-workers=1 --no-parallel`: PASS
- `./gradlew detekt --max-workers=1 --no-parallel`: PASS (up-to-date)
- Full test was not run because this was an architecture review with no code changes.
