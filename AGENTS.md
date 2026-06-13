# Project Context

## Overview

Rider plugin — one-click switch main repo + all submodules to preset branch combinations.

- **Stack**: Kotlin 2.3, IntelliJ Platform Gradle Plugin 2.2.1, Gradle 8.13, JUnit 4 + Kotest 5.9
- **Target**: JetBrains Rider 2026.1 (build 261)
- **Tests**: 188 tests, `./gradlew test`
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
./gradlew test          # 188 tests
./gradlew buildPlugin   # → build/distributions/rider-branch-switcher-{version}.zip
./gradlew runIde        # Launch sandbox Rider with plugin pre-installed
```

## User Preferences

- **No auto push**: Commit and stop. Only push when explicitly asked.
- **Cleanup stale tasks**: Check TaskList at session start/end; don't track already-completed tasks.

## Known Issues (from 2026-06-08 code review, NOT YET FIXED)

### Critical (data loss/corruption risk)
1. **PullStep runs on failed checkout**: If CheckoutStep fails (dirty conflict), PullStep still runs on old branch — can fast-forward main to dev.
2. **DirtyHandling Skip doesn't truly skip**: CheckoutStep doesn't know which paths were skipped, still checks out.
3. **Stash failure doesn't prevent checkout**: Dirty=Stash but stash fails → CheckoutStep still proceeds, dirty changes leak to new branch.
4. **Stash not popped when target branch doesn't exist**: Branch-not-found path in CheckoutStep skips stashPop.
5. **Gson + Kotlin defaults unreliable**: `Preset` data class uses Kotlin defaults (`emptyMap()`, `true`) but Gson uses UnsafeAllocator → missing JSON fields become null/false → NPE.

### High
6. TaskBridge missing `invokeOnCancellation` — parent coroutine cancel doesn't terminate IDE Task.
7. EDT violation: SwitchController modifies Swing on `Dispatchers.Default`.
8. `remoteName` uses alphabetical first remote, not `origin`-first.
9. User cancel treated as failure in SwitchPresetAction; `CancellationException` swallowed.
10. `onDerive` captures stale preset reference (constructor-time vs current).
11. `importPresets` doesn't validate fields.
12. `loadComboBranches` order: "loading..." string can be saved as branch name.
13. PresetEditor optimistic save: UI assumes saved before disk write completes.

### Medium
14. `detectGen` non-atomic (use AtomicLong).
15. `history` non-thread-safe + writes on failure.
16. PresetLoader IO exceptions escape `Result`.
17. SubmoduleSyncStep runs on failed main repo checkout.
18. Stash pop before PullStep → dirty working tree during pull.
19. `invokeLater` callbacks missing disposed guard.
20. GitOps `timeoutSeconds * 1000` int overflow risk.

### Low / Cleanup
21. 12 orphan i18n keys in properties files.
22. SwitchPresetAction missing `update()` / `getActionUpdateThread()`.
23. BranchSwitcherConfigurable missing `disposeUIResources()`.
24. `deleteEditor` doesn't remove vertical struts.
25. Toolbar button construction pattern repeated 7 times (extract helper).
26. Toolbar row naming chaos (row1/row1b/row2/row3).
27. Log toggle shows Icon.toString() garbage on initial render.
28. Dead code in `toggleLog` (both ternary branches identical).

Full details in `docs/code-review-2026-06-08.md`.

## Recent Changes (v0.6, 2026-06-07)

- Fixed 34 of 50 issues from comprehensive code review
- CI: GitHub Actions (test + verifyPlugin), Kotest property tests
- Settings Configurable page, dynamic remoteName detection
- History persistence, Gradle 8.13
- i18n: @PropertyKey compile-time validation, DynamicBundle
- Coroutines unification (TaskBridge pattern)
