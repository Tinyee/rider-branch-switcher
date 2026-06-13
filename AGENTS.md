# Project Context

## Overview

Rider plugin — one-click switch main repo + all submodules to preset branch combinations.

- **Stack**: Kotlin 2.3, IntelliJ Platform Gradle Plugin 2.2.1, Gradle 8.13, JUnit 4 + Kotest 5.9
- **Target**: JetBrains Rider 2026.1 (build 261)
- **Tests**: 220 tests, `./gradlew test`
- **Version**: 0.6.0

## Architecture

```
com.submodule.branchswitcher/
├── git/          GitClient (interface) + GitOps (CLI implementation)
├── model/        Preset, PresetFile, DirtyAction, SwitchOptions, PreflightRow
├── service/      BranchSwitcherService (Project Service, PersistentStateComponent)
├── switch/       SwitchStep pipeline + 5 Steps + SwitchExecutor + SwitchPreflight
├── ui/           Panel, Editor, Dialog, ComboUtil, Controller, ToolWindowFactory, ListManager
├── action/       SwitchPresetAction (Ctrl+Alt+B)
├── settings/     BranchSwitcherConfigurable
├── Bundle.kt     i18n (DynamicBundle, en/zh .properties)
├── Notifier.kt   IDE Notification wrapper
├── PresetLoader.kt  JSON load/save for .idea/branch-presets.json
└── TaskBridge.kt    suspend wrapper over ProgressManager (Task.Backgroundable)
```

### Key patterns
- **Switch pipeline**: `SwitchExecutor` runs `List<SwitchStep>` sequentially; each step returns `Ok | Partial(failures) | Fatal`. Only `Fatal` stops the pipeline.
- **GitClient interface**: All git ops go through `GitClient` interface (mockable for tests). `GitOps` is the CLI implementation.
- **Coroutines**: `BranchSwitcherService.scope` manages all async work. `TaskBridge` wraps `Task.Backgroundable` into suspend functions.
- **Persistence**: Presets in `.idea/branch-presets.json`, options in `branch-switcher.xml` (PersistentStateComponent).

## Dev Commands

```bash
./gradlew test          # 220 tests
./gradlew buildPlugin   # → build/distributions/rider-branch-switcher-{version}.zip
./gradlew runIde        # Launch sandbox Rider with plugin pre-installed
```

## User Preferences

- **No auto push**: Commit and stop. Only push when explicitly asked.
- **Cleanup stale tasks**: Check TaskList at session start/end; don't track already-completed tasks.

## Current Follow-ups

- Keep wall-clock performance measurements in a separate benchmark task; regular tests only enforce Git call budgets.
- Continue reduced manual release checks: narrow Tool Window, Settings UI, i18n, install built ZIP.
- UI cancel verified (2026-06-13): real Rider sandbox, cancelled mid-switch, `[cancelled]` logged, no exceptions, subsequent switches unaffected.
- Prepare Marketplace screenshots and pluginIcon.svg when ready to publish.

The original 2026-06-08 findings are archived in `docs/code-review-2026-06-08.md`; most have been fixed and the archive must not be treated as the current issue list.

## Recent Changes (v0.6, through 2026-06-13)

- Hardened switch safety: failed checkout gating, real Skip/Stash-failure behavior, delayed stash pop, and rollback failure coverage.
- Unified cancellation lifecycle across `TaskBridge`, `SwitchExecutor`, and `GitOps` with `beginOperation` / `cancel` / `endOperation`.
- Added stable Preset IDs, legacy ID normalization, history lookup by ID, and validated import rules via `PresetDto` / `parsePresetImport`.
- Added structured `AppLogger`, EDT-safe UI updates, origin-first remote selection, `jButton` factory, and reusable branch combo loading.
- Added Quick Start empty state and Settings Configurable.
- Added `TaskBridgeLifecycleTest` (9 tests) covering normal completion, block exception, user cancel, parent-cancel-before-run, parent-cancel-during-run, callback idempotency, runner sync throw, and callback exception containment.
- Fixed `TaskBridge.runBackground` race condition: parent coroutine cancel before `Task.run()` no longer allows block execution.
- Extracted injectable `TaskRunner` boundary enabling direct lifecycle testing without IntelliJ runtime.
- Added MIT `LICENSE`, automated `releaseCheck` Gradle task (test + detekt + buildPlugin + verifyPlugin + metadata validation).
- Added `DeriveBranchExecutor` with 4-phase pipeline (preflight, checkpoint, execute, rollback) and `deleteBranch` GitClient method for safe branch cleanup during rollback.
- 220 tests including: real Git integration, cancellation, rollback, derive safety (preflight+checkpoint+execute+rollback), notification decision, import, branch combo lifecycle, and 50-submodule Git call-budget checks.
- CI covers tests, plugin verification, Detekt, Kotest property tests, and Qodana.
