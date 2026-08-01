# Contributing

This guide contains contributor workflow, validation, and review rules. Project
usage belongs in the [README](README.md), environment and compatibility setup
in [docs/SETUP.md](docs/SETUP.md), and code structure in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Before You Start

1. Configure the environment described in [docs/SETUP.md](docs/SETUP.md).
2. Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before changing package
   ownership, Git lifecycle, switch state, or recovery behavior.
3. Enable the repository hooks after cloning:

```bash
git config core.hooksPath .githooks
```

Treat the architecture document as the source of truth for package ownership
and dependency direction. `quickCheck` enforces its important boundaries.

## Validation

Start with the narrowest check that exercises the changed behavior. Do not run
heavy Gradle tasks in parallel.

| Change | Minimum validation |
| --- | --- |
| Documentation only | `git diff --check` |
| Build scripts, i18n, or structural rules | `./gradlew quickCheck` and `git diff --check` |
| Pure JVM rules, JSON, validation, or core switching | `./gradlew :core:test --tests "<ClassOrMethod>" --rerun-tasks` |
| Platform, persistence, Git process, controller, or action behavior | `./gradlew :test --tests "<ClassOrMethod>" -x :core:test --rerun-tasks` |
| Cross-module or test-infrastructure changes | `./gradlew :core:test test :core:detekt detekt --rerun-tasks --max-workers=2 --no-parallel` |

The `-x :core:test` exclusion is required for filtered platform tests because
Gradle can otherwise apply the platform class filter to the core test task.
Use `--max-workers=1 --no-parallel` on constrained machines.

When updating a documented test total, start from clean reports:

```bash
./gradlew :core:cleanTest cleanTest :core:test test --rerun-tasks --max-workers=2 --no-parallel
```

For release preparation, run `./gradlew releaseCheck`. It checks the oldest and
current Rider endpoints locally; CI verifies every supported Rider platform
branch. The environment guide owns the exact SDK and verifier configuration.
For repeated install-from-disk testing, use the temporary
`localPluginVersion` override documented in
[docs/SETUP.md](docs/SETUP.md#plugin-versions-during-development); do not bump
formal release metadata for each local build.

## Optional Diagnostics

- `./gradlew benchmark` measures the real-Git 51-repository scenario without a
  wall-clock assertion.
- `./gradlew pitestCore` runs scoped mutation testing for pure decision logic.

Both tasks are intentionally outside normal tests and `releaseCheck`.

## Implementation And Review

- Fix a recurring failure pattern across equivalent call sites, not only the
  first instance found.
- Extract decisions that require substantial runtime setup into pure, testable
  logic. Prefer behavior assertions over data-class or language-feature tests.
- For switch, derive, rollback, and cancellation changes, enumerate success,
  partial failure, fatal failure, cancellation, and cleanup outcomes first.
- Add Git capabilities to the narrowest workflow interface that needs them.
- After changing exception propagation, inspect broad `catch` blocks so
  cancellation cannot be swallowed.
- Keep safety probes fail-closed when required repository state is unreadable.
- Keep i18n keys symmetric in both locale files.
- Keep user-facing changes and focused tests together; avoid unrelated
  refactors in the same commit.

## Commit Checklist

- Review the diff for unrelated generated or local files.
- Run the validation required for the changed behavior.
- Run `quickCheck` and `git diff --check` for every code change.
- Update user documentation when behavior changes.
- Update current test totals only from a clean full run; do not rewrite
  historical records.

Durable architecture, safety, and testing decisions belong in
[docs/review-history.md](docs/review-history.md). Keep active findings in the
relevant issue or pull request instead of using project documentation as a
temporary task tracker.
