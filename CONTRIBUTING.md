# Contributing

This guide describes the local development, testing, and review conventions for
Submodule Branch Switcher. It applies to every contributor and automation.

## Project Overview

This is a JetBrains IDE plugin that switches a main Git repository and its
submodules to a saved branch preset.

- Kotlin 2.3, Gradle 8.13, IntelliJ Platform Gradle Plugin 2.2.1
- Target: JetBrains IDEs 2026.1 (build 261)
- Default SDK: IntelliJ IDEA Community; Rider is a compatibility target
- Tests: 310 tests / 30 classes (159 core, 151 platform/integration; 4 Kotest
  property tests; benchmark excluded)

Use JDK 21. The Gradle build configures the Kotlin toolchain accordingly.
The default SDK configuration is in `gradle.properties` (`platform.type=IC`,
`platform.version=2026.1.3`); use `platform.localPath` for a local IDE install.

## Architecture

The current architecture is documented in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Read it before changing package
ownership, Git lifecycle, switch state, or recovery behavior.

`core/` is a pure JVM module and must not depend on IntelliJ APIs. It contains
the preset model and persistence, Git interfaces, switch pipeline, preflight,
and pure UI decision rules.

`src/` is the IntelliJ Platform module. Its `git/` package implements CLI
access, `platform/` contains IntelliJ adapters, `workflow/` contains application
use cases, `service/` owns project-scoped state and preset access, and `ui/`
owns presentation and user interaction.

Key boundaries:

- Git consumers depend on workflow-specific interfaces; `GitClient` is the
  aggregate implementation boundary and `GitOps` is the CLI implementation.
- `GitOps` is a facade over `GitCommandClient`; only `GitProcessRunner` owns
  command process lifecycle. Each operation receives its own command client and
  cancellation token.
- `SwitchExecutor` runs ordered `SwitchStep`s and returns a structured
  `SwitchExecutionResult`. Steps explicitly return the immutable `SwitchState`
  passed to the next step.
- Checkpoint capture and recovery are separate from forward execution:
  `SwitchCheckpointRecorder` records rollback state and
  `SwitchRecoveryExecutor` owns rollback and pending stash restoration.
- Application workflows may depend on platform adapters, while `platform/` and
  `service/` must not depend back on `workflow/` or `ui/`; `quickCheck` enforces
  these package directions.
- Presets use project-local JSON resolved by `PresetLoader`; global options use
  a `PersistentStateComponent`.
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
| Pure JVM rules, JSON, validation, core switch decisions | `./gradlew :core:test --tests "<ClassOrMethod>" --rerun-tasks` |
| Platform, persistence, Git process, controller, or action behavior | `./gradlew :test --tests "<ClassOrMethod>" -x :core:test --rerun-tasks` |
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
consolidated in `docs/review-history.md`. The documentation index is
`docs/README.md`; dated planning documents listed there are historical, not
active task lists.
