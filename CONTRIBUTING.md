# Contributing

This guide describes the local development, testing, and review conventions for
Submodule Branch Switcher. It applies to every contributor and automation.

## Project Overview

This is a JetBrains IDE plugin that switches a main Git repository and its
submodules to a saved branch preset.

- Kotlin 2.3, Gradle 8.13, IntelliJ Platform Gradle Plugin 2.2.1
- Target: JetBrains IDEs 2026.1 (build 261)
- Default SDK: IntelliJ IDEA Community; Rider is a compatibility target
- Tests: 296 tests / 28 classes (153 core, 143 platform/integration; 4 Kotest
  property tests; benchmark excluded)

Use JDK 21. The Gradle build configures the Kotlin toolchain accordingly.
The default SDK configuration is in `gradle.properties` (`platform.type=IC`,
`platform.version=2026.1.3`); use `platform.localPath` for a local IDE install.

## Architecture

`core/` is a pure JVM module and must not depend on IntelliJ APIs. It contains
the preset model and persistence, Git interfaces, switch pipeline, preflight,
and pure UI decision rules.

`src/` is the IntelliJ Platform module. It implements Git CLI access,
project services, background-task adapters, IDE UI, actions, notifications, and
i18n.

Key boundaries:

- Git consumers depend on workflow-specific interfaces; `GitClient` is the
  aggregate implementation boundary and `GitOps` is the CLI implementation.
- `SwitchExecutor` runs ordered `SwitchStep`s and returns a structured
  `SwitchExecutionResult`. Steps explicitly return the immutable `SwitchState`
  passed to the next step.
- Presets live in `.idea/branch-presets.json`; options use a
  `PersistentStateComponent`.
- A write operation acquires a scoped `WriteLease`, runs Git work through
  `GitBackgroundRunner`, and closes the lease in `finally`.
- `GitBackgroundRunner` owns an isolated `GitOperationSession`, including
  cancellation and close handling. Production code must not call
  `TaskBridge.runBackground` directly.
- Cancellation must be rethrown or converted into an explicit cancelled
  outcome, never an ordinary error. Safety probes fail closed when state cannot
  be determined.

## Setup And Commands

Enable local hooks once after cloning:

```bash
git config core.hooksPath .githooks
```

Common commands:

```bash
./gradlew quickCheck
./gradlew pureTest
./gradlew test
./gradlew buildPlugin
./gradlew runIde
```

`releaseCheck` is a release-only command. `benchmark` and `pitestCore` are
manual diagnostics and are intentionally outside normal test runs.

## Verification

Prefer the narrowest meaningful validation first. Do not run heavy Gradle tasks
in parallel.

| Change | Minimum validation |
| --- | --- |
| Documentation only | `git diff --check` |
| Build scripts, i18n, lightweight call-site changes | `quickCheck` + `git diff --check` |
| Pure JVM rules, JSON, import, settings | Related `:core:test --tests ... --rerun-tasks` |
| Platform, persistence, Git, cancellation, controller, or action behavior | Related `test --tests ... --rerun-tasks` |
| Cross-module or test-infrastructure changes | `:core:test test :core:detekt detekt --rerun-tasks` |

Use low-load limits for broad local validation:

```bash
./gradlew :core:test test :core:detekt detekt --rerun-tasks --max-workers=2 --no-parallel
```

Use `--max-workers=1 --no-parallel` on constrained machines. Do not reduce test
coverage or property-test iterations merely to lower resource use.

When updating documented test totals, start from clean test outputs; targeted or
incremental runs can leave XML reports for tests that were not part of the
current invocation:

```bash
./gradlew :core:cleanTest cleanTest :core:test test --rerun-tasks --max-workers=2 --no-parallel
```

## Implementation And Review

- Fix a recurring failure pattern across equivalent call sites, not only the
  first instance found.
- Extract decisions that need substantial runtime setup into pure, testable
  logic early. Prefer behavior assertions over data-class or language-feature
  assertions.
- For switch, derive, rollback, and cancellation changes, enumerate success,
  partial failure, fatal failure, cancellation, and cleanup outcomes before
  implementation.
- Add Git capabilities to the narrowest workflow interface that needs them.
  Conservative defaults may be used when they keep test fakes source
  compatible without weakening production safety.
- After changing exception propagation, inspect broad `catch` blocks in callers
  so cancellation cannot be swallowed.
- Keep i18n keys symmetric in both locale files.
- Keep user-facing changes and their focused tests together. Avoid unrelated
  refactors in the same change.

## Before Committing

Run `quickCheck` and `git diff --check` for every code change. For a normal
cross-module commit, also run:

```bash
./gradlew :core:test test :core:detekt detekt --max-workers=2 --no-parallel
```

Synchronize current test counts and status across active documentation in one
batch. Do not rewrite historical review records merely to update a current
number.

Keep active findings in the relevant issue or pull request. Durable
architecture, safety, and testing decisions from completed reviews are
consolidated in `docs/review-history.md`.
